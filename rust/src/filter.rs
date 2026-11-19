//! [`FilterBuilder`]: renders SigNoz filter expressions (query builder v5 search syntax).

use std::fmt;

use crate::core::{Error, Result};

/// A value in a filter expression: strings are quoted and escaped, numbers and booleans are
/// rendered bare.
#[derive(Debug, Clone, PartialEq)]
pub enum FilterValue {
    /// A string, rendered as `'…'` with `\` and `'` escaped.
    Str(String),
    /// A signed integer.
    Int(i64),
    /// An unsigned integer.
    UInt(u64),
    /// A float (must be finite).
    Float(f64),
    /// `true` / `false`.
    Bool(bool),
}

macro_rules! filter_value_from {
    ($variant:ident <- $($t:ty),+) => {$(
        impl From<$t> for FilterValue {
            fn from(v: $t) -> Self {
                FilterValue::$variant(v.into())
            }
        }
    )+};
}
filter_value_from!(Str <- &str, String, &String);
filter_value_from!(Int <- i8, i16, i32, i64);
filter_value_from!(UInt <- u8, u16, u32, u64);
filter_value_from!(Float <- f32, f64);
filter_value_from!(Bool <- bool);

impl From<usize> for FilterValue {
    fn from(v: usize) -> Self {
        FilterValue::UInt(v as u64)
    }
}

/// Comparison operator for [`FilterBuilder::where_op`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FilterOperator {
    /// `=`
    Eq,
    /// `!=`
    Ne,
    /// `<`
    Lt,
    /// `<=`
    Lte,
    /// `>`
    Gt,
    /// `>=`
    Gte,
}

impl FilterOperator {
    /// The operator text (`=`, `!=`, `<`, `<=`, `>`, `>=`).
    pub fn as_str(self) -> &'static str {
        match self {
            FilterOperator::Eq => "=",
            FilterOperator::Ne => "!=",
            FilterOperator::Lt => "<",
            FilterOperator::Lte => "<=",
            FilterOperator::Gt => ">",
            FilterOperator::Gte => ">=",
        }
    }
}

/// Builds SigNoz filter expressions, for example
///
/// ```
/// use grow_signoz::FilterBuilder;
///
/// let f = FilterBuilder::new()
///     .eq("service.name", "checkout")
///     .in_list("severity_text", ["ERROR", "FATAL"]);
/// assert_eq!(f.build().unwrap(), "service.name = 'checkout' AND severity_text IN ('ERROR', 'FATAL')");
/// ```
///
/// Conditions are joined with `AND`; use [`or`](Self::or) and [`not`](Self::not) to group.
/// Values are quoted and escaped; [`contains`](Self::contains) also escapes the LIKE wildcards
/// `%` and `_` so the text matches literally. Every method returns a new builder, so a base
/// filter can be cloned and reused. Invalid input (a bad field name, an empty `IN` list, a
/// non-finite number) is reported by [`build`](Self::build) as `VALIDATION`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct FilterBuilder {
    parts: Vec<String>,
    error: Option<String>,
}

impl FilterBuilder {
    /// An empty filter (matches everything).
    pub fn new() -> Self {
        FilterBuilder::default()
    }

    fn with(mut self, expr: String, error: Option<String>) -> Self {
        if !expr.is_empty() {
            self.parts.push(expr);
        }
        if self.error.is_none() {
            self.error = error;
        }
        self
    }

    fn fail(self, error: String) -> Self {
        self.with(String::new(), Some(error))
    }

    fn cond(self, prefix: &str, field: &str, rest: Result<String>) -> Self {
        if let Err(e) = check_field(field) {
            return self.fail(e.to_string());
        }
        match rest {
            Ok(rest) => self.with(format!("{prefix}{field} {rest}"), None),
            Err(e) => self.fail(e.to_string()),
        }
    }

    /// Adds `field <op> value` (Go `Where`, TypeScript `where`).
    pub fn where_op(self, field: &str, op: FilterOperator, value: impl Into<FilterValue>) -> Self {
        let rest = filter_literal(&value.into()).map(|lit| format!("{} {lit}", op.as_str()));
        self.cond("", field, rest)
    }

    /// Adds `field = value`.
    pub fn eq(self, field: &str, value: impl Into<FilterValue>) -> Self {
        self.where_op(field, FilterOperator::Eq, value)
    }
    /// Adds `field != value`.
    pub fn ne(self, field: &str, value: impl Into<FilterValue>) -> Self {
        self.where_op(field, FilterOperator::Ne, value)
    }
    /// Adds `field < value`.
    pub fn lt(self, field: &str, value: impl Into<FilterValue>) -> Self {
        self.where_op(field, FilterOperator::Lt, value)
    }
    /// Adds `field <= value`.
    pub fn lte(self, field: &str, value: impl Into<FilterValue>) -> Self {
        self.where_op(field, FilterOperator::Lte, value)
    }
    /// Adds `field > value`.
    pub fn gt(self, field: &str, value: impl Into<FilterValue>) -> Self {
        self.where_op(field, FilterOperator::Gt, value)
    }
    /// Adds `field >= value`.
    pub fn gte(self, field: &str, value: impl Into<FilterValue>) -> Self {
        self.where_op(field, FilterOperator::Gte, value)
    }

    /// Adds `field IN (v1, v2, …)` (at least one value; Go `In`, TypeScript `in`).
    pub fn in_list<V: Into<FilterValue>>(
        self,
        field: &str,
        values: impl IntoIterator<Item = V>,
    ) -> Self {
        let rest = list(values).map(|l| format!("IN {l}"));
        self.cond("", field, rest)
    }

    /// Adds `field NOT IN (v1, v2, …)` (at least one value).
    pub fn not_in<V: Into<FilterValue>>(
        self,
        field: &str,
        values: impl IntoIterator<Item = V>,
    ) -> Self {
        let rest = list(values).map(|l| format!("NOT IN {l}"));
        self.cond("", field, rest)
    }

    /// Adds `field CONTAINS 'text'` matching `text` literally (case-insensitive; `%`, `_` and
    /// `\` are escaped).
    pub fn contains(self, field: &str, text: &str) -> Self {
        let rest = format!(
            "CONTAINS {}",
            quote_filter_string(&escape_like_pattern(text))
        );
        self.cond("", field, Ok(rest))
    }

    /// Adds `NOT field CONTAINS 'text'` (`text` matched literally).
    pub fn not_contains(self, field: &str, text: &str) -> Self {
        let rest = format!(
            "CONTAINS {}",
            quote_filter_string(&escape_like_pattern(text))
        );
        self.cond("NOT ", field, Ok(rest))
    }

    /// Adds `field LIKE 'pattern'`; `%` and `_` in `pattern` are wildcards.
    pub fn like(self, field: &str, pattern: &str) -> Self {
        let rest = format!("LIKE {}", quote_filter_string(pattern));
        self.cond("", field, Ok(rest))
    }

    /// Adds `field REGEXP 'pattern'` (RE2 syntax, evaluated by ClickHouse).
    pub fn regexp(self, field: &str, pattern: &str) -> Self {
        let rest = format!("REGEXP {}", quote_filter_string(pattern));
        self.cond("", field, Ok(rest))
    }

    /// Adds `field EXISTS`.
    pub fn exists(self, field: &str) -> Self {
        self.cond("", field, Ok("EXISTS".to_owned()))
    }

    /// Adds `field NOT EXISTS`.
    pub fn not_exists(self, field: &str) -> Self {
        self.cond("", field, Ok("NOT EXISTS".to_owned()))
    }

    /// Adds a quoted full-text term that searches the log body.
    pub fn text(self, value: &str) -> Self {
        self.with(quote_filter_string(value), None)
    }

    /// Adds `expression` unchanged; it is parenthesised when it contains `OR`.
    pub fn raw(self, expression: &str) -> Self {
        let t = expression.trim();
        if t.is_empty() {
            return self;
        }
        let t = if contains_or(t) {
            format!("({t})")
        } else {
            t.to_owned()
        };
        self.with(t, None)
    }

    /// Adds `((a) OR (b) …)` built from the non-empty alternatives (`(a)` for a single one).
    pub fn or(self, alternatives: impl IntoIterator<Item = FilterBuilder>) -> Self {
        let mut parts = Vec::new();
        let mut error = None;
        for a in alternatives {
            if error.is_none() {
                error = a.error.clone();
            }
            let s = a.expression();
            if !s.is_empty() {
                parts.push(s);
            }
        }
        let expr = match parts.len() {
            0 => String::new(),
            1 => format!("({})", parts[0]),
            _ => format!("(({}))", parts.join(") OR (")),
        };
        self.with(expr, error)
    }

    /// Adds `NOT (g)`.
    pub fn not(self, group: FilterBuilder) -> Self {
        let s = group.expression();
        let expr = if s.is_empty() {
            String::new()
        } else {
            format!("NOT ({s})")
        };
        self.with(expr, group.error)
    }

    /// Whether no condition was added.
    pub fn is_empty(&self) -> bool {
        self.parts.is_empty()
    }

    /// Renders the expression (`""` when empty), ignoring validation errors; use
    /// [`build`](Self::build) to see them.
    pub fn expression(&self) -> String {
        self.parts.join(" AND ")
    }

    /// Renders the expression or returns the first `VALIDATION` error.
    pub fn build(&self) -> Result<String> {
        match &self.error {
            Some(e) => Err(Error::validation(e.clone())),
            None => Ok(self.expression()),
        }
    }
}

impl fmt::Display for FilterBuilder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.expression())
    }
}

impl From<FilterBuilder> for String {
    fn from(f: FilterBuilder) -> Self {
        f.expression()
    }
}

/// Field names accepted by the builder: `[A-Za-z0-9_.:-]+`.
fn check_field(name: &str) -> Result<()> {
    let ok = !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | ':' | '-'));
    if ok {
        Ok(())
    } else {
        Err(Error::validation(format!(
            "filter: invalid field name {name:?}"
        )))
    }
}

pub(crate) fn validate_field(name: &str) -> Result<()> {
    check_field(name)
}

/// Whether `text` contains the word `OR` (case-insensitive).
fn contains_or(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let word = |b: u8| b.is_ascii_alphanumeric() || b == b'_';
    lower.match_indices("or").any(|(i, _)| {
        let before = i == 0 || !word(bytes[i - 1]);
        let after = i + 2 >= bytes.len() || !word(bytes[i + 2]);
        before && after
    })
}

fn list<V: Into<FilterValue>>(values: impl IntoIterator<Item = V>) -> Result<String> {
    let rendered = values
        .into_iter()
        .map(|v| filter_literal(&v.into()))
        .collect::<Result<Vec<_>>>()?;
    if rendered.is_empty() {
        return Err(Error::validation("filter: IN lists must not be empty"));
    }
    Ok(format!("({})", rendered.join(", ")))
}

/// Joins non-empty expressions with `AND`, parenthesising each when there is more than one.
pub fn and_filters<S: AsRef<str>>(parts: impl IntoIterator<Item = S>) -> String {
    let out: Vec<String> = parts
        .into_iter()
        .map(|p| p.as_ref().trim().to_owned())
        .filter(|p| !p.is_empty())
        .collect();
    match out.len() {
        0 => String::new(),
        1 => out[0].clone(),
        _ => format!("({})", out.join(") AND (")),
    }
}

/// Quotes `s` in single quotes with `\` and `'` escaped.
pub fn quote_filter_string(s: &str) -> String {
    format!("'{}'", s.replace('\\', "\\\\").replace('\'', "\\'"))
}

/// Escapes `\`, `%` and `_` so `text` matches literally inside `CONTAINS` or `LIKE` (quote it
/// afterwards).
pub fn escape_like_pattern(text: &str) -> String {
    text.replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_")
}

/// Renders a filter value: strings quoted ([`quote_filter_string`]), booleans `true`/`false`,
/// numbers bare. Non-finite floats are `VALIDATION` errors.
pub fn filter_literal(value: &FilterValue) -> Result<String> {
    Ok(match value {
        FilterValue::Str(s) => quote_filter_string(s),
        FilterValue::Bool(b) => b.to_string(),
        FilterValue::Int(i) => i.to_string(),
        FilterValue::UInt(u) => u.to_string(),
        FilterValue::Float(f) if f.is_finite() => f.to_string(),
        FilterValue::Float(_) => {
            return Err(Error::validation("filter: numbers must be finite"));
        }
    })
}

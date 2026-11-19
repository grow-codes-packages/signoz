//! Count / percentile aggregations ([`AggregateBuilder`], [`SignozQueryClient::aggregate`]).

use std::collections::BTreeMap;
use std::time::{Duration, SystemTime};

use serde::Serialize;
use serde_json::{json, Map, Value};

use crate::core::{Error, Result};
use crate::filter::validate_field;
use crate::logging::AttrValue;
use crate::query::{
    check_limit, filter_spec, from_millis, num_of, str_of, Signal, SignozQueryClient, TimeRange,
    DEFAULT_SEARCH_LIMIT,
};

/// One aggregate expression and the name its values get in the result.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Aggregation {
    /// A SigNoz aggregate, for example `count()`, `p99(duration_nano)`, `avg(duration_nano)`,
    /// `count_distinct(trace_id)`.
    pub expression: String,
    /// Names the value in [`AggregateRow::values`] / [`AggregateSeries::alias`] (default: the
    /// expression).
    pub alias: Option<String>,
}

impl Aggregation {
    /// An aggregation with an alias.
    pub fn new(expression: impl Into<String>, alias: impl Into<String>) -> Self {
        let alias = alias.into();
        Aggregation {
            expression: expression.into(),
            alias: (!alias.is_empty()).then_some(alias),
        }
    }

    fn name(&self) -> String {
        self.alias
            .clone()
            .unwrap_or_else(|| self.expression.clone())
    }
}

/// A count/percentile query for [`SignozQueryClient::aggregate`]; build it with
/// [`AggregateBuilder`] or as a literal.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AggregateQuery {
    /// Signal to aggregate.
    pub signal: Signal,
    /// SigNoz filter expression (empty = all).
    pub filter: String,
    /// Values to compute (at least one).
    pub aggregations: Vec<Aggregation>,
    /// Field names to group by (for example `service.name`, `name`).
    pub group_by: Vec<String>,
    /// Window start (default end − 1 h).
    pub start: Option<SystemTime>,
    /// Window end (default now).
    pub end: Option<SystemTime>,
    /// `Some(step)` returns time series with that bucket size (rounded to seconds, at least
    /// 1 s); `None` (or zero) returns one row per group (scalar).
    pub step_interval: Option<Duration>,
    /// Caps the number of groups (0 = default 100, at most
    /// [`MAX_QUERY_LIMIT`](crate::MAX_QUERY_LIMIT)).
    pub limit: usize,
    /// Orders groups: a group field name or an aggregation alias, optionally followed by
    /// ` asc` or ` desc` (default desc).
    pub order_by: Option<String>,
}

/// One group of a scalar aggregate.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct AggregateRow {
    /// Each group-by field and its value (string, number or bool).
    pub groups: BTreeMap<String, AttrValue>,
    /// Each aggregation alias and its value.
    pub values: BTreeMap<String, f64>,
}

/// One bucket of a time series.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct AggregatePoint {
    /// Bucket start.
    pub time: SystemTime,
    /// Value.
    pub value: f64,
}

/// One time series of a time-series aggregate.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct AggregateSeries {
    /// The aggregation the series belongs to.
    pub alias: String,
    /// Each group-by field and the series' value.
    pub labels: BTreeMap<String, AttrValue>,
    /// Buckets in time order.
    pub points: Vec<AggregatePoint>,
}

/// Result of [`SignozQueryClient::aggregate`]: `rows` for scalar queries, `series` for time
/// series.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct AggregateResult {
    /// Scalar rows.
    pub rows: Vec<AggregateRow>,
    /// Time series.
    pub series: Vec<AggregateSeries>,
    /// Server warnings (for example unknown fields in the filter).
    pub warnings: Vec<String>,
}

/// Builds an [`AggregateQuery`] fluently:
///
/// ```
/// use grow_signoz::{AggregateBuilder, Signal};
///
/// let q = AggregateBuilder::new(Signal::Traces)
///     .filter("service.name = 'checkout'")
///     .count("calls")
///     .percentile(99, "duration_nano", "p99")
///     .group_by(["name"])
///     .query();
/// assert_eq!(q.aggregations.len(), 2);
/// ```
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AggregateBuilder {
    q: AggregateQuery,
}

impl AggregateBuilder {
    /// Starts an aggregate over `signal`.
    pub fn new(signal: Signal) -> Self {
        AggregateBuilder {
            q: AggregateQuery {
                signal,
                ..AggregateQuery::default()
            },
        }
    }
    /// Sets the filter expression (Go `Where`).
    pub fn filter(mut self, filter: impl Into<String>) -> Self {
        self.q.filter = filter.into();
        self
    }
    /// Adds `count()` named `alias`.
    pub fn count(self, alias: &str) -> Self {
        self.expr("count()", alias)
    }
    /// Adds `count_distinct(field)` named `alias`.
    pub fn count_distinct(self, field: &str, alias: &str) -> Self {
        self.expr(&format!("count_distinct({field})"), alias)
    }
    /// Adds `pNN(field)` named `alias` (`p` is 50, 75, 90, 95, 99, …).
    pub fn percentile(self, p: u32, field: &str, alias: &str) -> Self {
        self.expr(&format!("p{p}({field})"), alias)
    }
    /// Adds `avg(field)` named `alias`.
    pub fn avg(self, field: &str, alias: &str) -> Self {
        self.expr(&format!("avg({field})"), alias)
    }
    /// Adds `sum(field)` named `alias`.
    pub fn sum(self, field: &str, alias: &str) -> Self {
        self.expr(&format!("sum({field})"), alias)
    }
    /// Adds `min(field)` named `alias`.
    pub fn min(self, field: &str, alias: &str) -> Self {
        self.expr(&format!("min({field})"), alias)
    }
    /// Adds `max(field)` named `alias`.
    pub fn max(self, field: &str, alias: &str) -> Self {
        self.expr(&format!("max({field})"), alias)
    }
    /// Adds any aggregate expression named `alias` (empty alias = the expression).
    pub fn expr(mut self, expression: &str, alias: &str) -> Self {
        self.q
            .aggregations
            .push(Aggregation::new(expression, alias));
        self
    }
    /// Adds group fields.
    pub fn group_by<S: Into<String>>(mut self, fields: impl IntoIterator<Item = S>) -> Self {
        self.q.group_by.extend(fields.into_iter().map(Into::into));
        self
    }
    /// Sets the time window.
    pub fn between(mut self, start: SystemTime, end: SystemTime) -> Self {
        self.q.start = Some(start);
        self.q.end = Some(end);
        self
    }
    /// Makes the query a time series with buckets of `step`.
    pub fn step(mut self, step: Duration) -> Self {
        self.q.step_interval = Some(step);
        self
    }
    /// Caps the number of groups.
    pub fn limit(mut self, limit: usize) -> Self {
        self.q.limit = limit;
        self
    }
    /// Sets the group order (`"calls desc"`, `"name asc"`).
    pub fn order_by(mut self, order: impl Into<String>) -> Self {
        self.q.order_by = Some(order.into());
        self
    }
    /// The built query.
    pub fn query(&self) -> AggregateQuery {
        self.q.clone()
    }
}

impl From<AggregateBuilder> for AggregateQuery {
    fn from(b: AggregateBuilder) -> Self {
        b.q
    }
}

/// The group-by field reference; `service.name` is a resource field, `name` a span field.
fn group_field(name: &str) -> Value {
    match name {
        "service.name" => {
            json!({"name": name, "fieldContext": "resource", "fieldDataType": "string"})
        }
        "name" => json!({"name": name, "fieldContext": "span", "fieldDataType": "string"}),
        _ => json!({ "name": name }),
    }
}

fn alias_for(aliases: &[String], index: i64) -> String {
    usize::try_from(index)
        .ok()
        .and_then(|i| aliases.get(i).cloned())
        .unwrap_or_else(|| format!("__result_{index}"))
}

impl SignozQueryClient {
    /// Runs an aggregate query (`POST /api/v5/query_range`, request type `scalar` or
    /// `time_series`). SigNoz does not echo aliases (columns are named `__result_<i>`); values
    /// are mapped back to the caller's aliases by index.
    pub async fn aggregate(&self, query: impl Into<AggregateQuery>) -> Result<AggregateResult> {
        let op = "aggregate";
        let aq = query.into();
        if aq.aggregations.is_empty() {
            return Err(Error::validation(format!(
                "{op}: at least one aggregation is required"
            )));
        }
        let limit = check_limit(op, aq.limit, DEFAULT_SEARCH_LIMIT)?;
        let mut spec = Map::new();
        spec.insert("signal".into(), json!(aq.signal.as_str()));
        if let Some(f) = filter_spec(&aq.filter) {
            spec.insert("filter".into(), f);
        }
        spec.insert("limit".into(), json!(limit));
        let mut aliases = Vec::new();
        let mut aggs = Vec::new();
        for a in &aq.aggregations {
            if a.expression.trim().is_empty() {
                return Err(Error::validation(format!(
                    "{op}: aggregation expression is empty"
                )));
            }
            aliases.push(a.name());
            let mut v = json!({ "expression": a.expression });
            if let Some(alias) = &a.alias {
                v["alias"] = json!(alias);
            }
            aggs.push(v);
        }
        spec.insert("aggregations".into(), Value::Array(aggs));
        if !aq.group_by.is_empty() {
            let mut groups = Vec::new();
            for g in &aq.group_by {
                validate_field(g)?;
                groups.push(group_field(g));
            }
            spec.insert("groupBy".into(), Value::Array(groups));
        }
        if let Some(order) = aq
            .order_by
            .as_deref()
            .map(str::trim)
            .filter(|o| !o.is_empty())
        {
            let (key, dir) = order.split_once(' ').unwrap_or((order, ""));
            let dir = match dir.trim().to_ascii_lowercase().as_str() {
                "" | "desc" => "desc",
                "asc" => "asc",
                _ => {
                    return Err(Error::validation(format!(
                        "{op}: orderBy direction must be asc or desc"
                    )))
                }
            };
            spec.insert(
                "order".into(),
                json!([{"key": {"name": key}, "direction": dir}]),
            );
        }
        let request_type = match aq.step_interval {
            Some(step) if !step.is_zero() => {
                spec.insert("stepInterval".into(), json!(step.as_secs().max(1)));
                "time_series"
            }
            _ => "scalar",
        };
        let (start, end) = TimeRange {
            start: aq.start,
            end: aq.end,
        }
        .resolve();
        let (raw, warnings) = self.query_range(op, request_type, start, end, spec).await?;
        let mut out = AggregateResult {
            warnings,
            ..AggregateResult::default()
        };
        let Some(raw) = raw else {
            return Ok(out);
        };
        if request_type == "scalar" {
            out.rows = parse_scalar(&raw, &aliases);
        } else {
            out.series = parse_time_series(&raw, &aliases);
        }
        Ok(out)
    }
}

fn parse_scalar(raw: &Value, aliases: &[String]) -> Vec<AggregateRow> {
    let columns = raw
        .get("columns")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut rows = Vec::new();
    for row in raw
        .get("data")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let cells = row.as_array().cloned().unwrap_or_default();
        let mut r = AggregateRow::default();
        for (col, cell) in columns.iter().zip(cells.iter()) {
            if str_of(col, "columnType") == "aggregation" {
                let name = alias_for(aliases, num_of(col, "aggregationIndex").unwrap_or(0));
                if let Some(f) = cell.as_f64() {
                    r.values.insert(name, f);
                }
            } else {
                r.groups.insert(str_of(col, "name"), cell.clone());
            }
        }
        rows.push(r);
    }
    rows
}

fn parse_time_series(raw: &Value, aliases: &[String]) -> Vec<AggregateSeries> {
    let mut out = Vec::new();
    for agg in raw
        .get("aggregations")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let alias = alias_for(aliases, num_of(agg, "index").unwrap_or(0));
        for s in agg
            .get("series")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let mut series = AggregateSeries {
                alias: alias.clone(),
                ..AggregateSeries::default()
            };
            for l in s
                .get("labels")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                let key = l.get("key").map(|k| str_of(k, "name")).unwrap_or_default();
                series
                    .labels
                    .insert(key, l.get("value").cloned().unwrap_or(Value::Null));
            }
            for v in s
                .get("values")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                series.points.push(AggregatePoint {
                    time: from_millis(num_of(v, "timestamp").unwrap_or(0)),
                    value: v.get("value").and_then(Value::as_f64).unwrap_or(0.0),
                });
            }
            out.push(series);
        }
    }
    out
}

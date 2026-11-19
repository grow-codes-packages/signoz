//! Logs queries: `query_logs` with the SDK's own cursor, log fields, field values, and the
//! [`LogQueryClient`] implementation.

use std::collections::BTreeSet;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use crate::core::{Error, Method, Result};
use crate::filter::{and_filters, quote_filter_string, validate_field};
use crate::logging::{
    AttrValue, LogQueryClient, LogRecord, LogSearchResult, RecordCallback, SearchOptions, Severity,
    SortOrder, TailHandle, TailOptions,
};
use crate::query::{
    attrs_of, check_limit, filter_spec, from_millis, millis, num_of, str_of, text_of, Signal,
    SignozQueryClient, TimeRange, DEFAULT_SEARCH_LIMIT,
};

/// A logs query for [`SignozQueryClient::query_logs`]. The default lists the newest 100
/// records of the last hour.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LogQuery {
    /// SigNoz filter expression (build one with [`FilterBuilder`](crate::FilterBuilder)), for
    /// example `service.name = 'checkout' AND severity_text IN ('ERROR')`. Bare words search the
    /// body (`timeout` or `'connection reset'`). Empty matches all.
    pub filter: String,
    /// Window start (default end − 1 h).
    pub start: Option<SystemTime>,
    /// Window end, exclusive (default now).
    pub end: Option<SystemTime>,
    /// Page size (0 = default 100, at most [`MAX_QUERY_LIMIT`](crate::MAX_QUERY_LIMIT)).
    pub limit: usize,
    /// Newest first (default) or oldest first.
    pub order: SortOrder,
    /// Continues a previous page ([`LogQueryResult::next_cursor`]); pass the same filter.
    pub cursor: Option<String>,
    /// Limits the returned columns (`id` and `timestamp` are always returned). Empty returns
    /// every column.
    pub select_fields: Vec<String>,
}

impl LogQuery {
    /// A query with this filter expression.
    pub fn new(filter: impl Into<String>) -> Self {
        LogQuery {
            filter: filter.into(),
            ..LogQuery::default()
        }
    }
    /// Sets the time window.
    pub fn range(mut self, start: Option<SystemTime>, end: Option<SystemTime>) -> Self {
        self.start = start;
        self.end = end;
        self
    }
    /// Sets the page size.
    pub fn limit(mut self, limit: usize) -> Self {
        self.limit = limit;
        self
    }
    /// Sets the order.
    pub fn order(mut self, order: SortOrder) -> Self {
        self.order = order;
        self
    }
    /// Sets the cursor.
    pub fn cursor(mut self, cursor: impl Into<String>) -> Self {
        self.cursor = Some(cursor.into());
        self
    }
    /// Sets the returned columns.
    pub fn select_fields<S: Into<String>>(mut self, fields: impl IntoIterator<Item = S>) -> Self {
        self.select_fields = fields.into_iter().map(Into::into).collect();
        self
    }
}

/// One page of [`SignozQueryClient::query_logs`].
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogQueryResult {
    /// Normalized records of the page.
    pub records: Vec<LogRecord>,
    /// Set when the page is full; pass it back as [`LogQuery::cursor`] for the next page.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
    /// Server warnings, for example an unknown field in the filter (SigNoz then answers with no
    /// rows instead of an error).
    pub warnings: Vec<String>,
}

/// The SDK's own keyset cursor (same wire form in every language). The server's `nextCursor`
/// is not used: it is the last row's millisecond and skips the other rows of that
/// millisecond. The next page instead repeats the boundary millisecond and excludes the ids
/// already returned in it.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct LogCursor {
    v: u32,
    s: i64,
    e: i64,
    o: String,
    m: i64,
    #[serde(default)]
    x: Option<Vec<String>>,
}

fn order_str(o: SortOrder) -> &'static str {
    match o {
        SortOrder::Asc => "asc",
        SortOrder::Desc => "desc",
    }
}

fn encode_cursor(c: &LogCursor) -> String {
    URL_SAFE_NO_PAD.encode(serde_json::to_vec(c).unwrap_or_default())
}

fn decode_cursor(op: &str, s: &str) -> Result<(LogCursor, SortOrder)> {
    let bad = || Error::validation(format!("{op}: invalid cursor"));
    let bytes = URL_SAFE_NO_PAD.decode(s.trim()).map_err(|_| bad())?;
    let c: LogCursor = serde_json::from_slice(&bytes).map_err(|_| bad())?;
    let order = match (c.v, c.o.as_str()) {
        (1, "asc") => SortOrder::Asc,
        (1, "desc") => SortOrder::Desc,
        _ => return Err(bad()),
    };
    Ok((c, order))
}

/// `id NOT IN ('a', 'b')` (empty for no ids).
fn not_in_ids(ids: &[String]) -> String {
    if ids.is_empty() {
        return String::new();
    }
    let quoted: Vec<String> = ids.iter().map(|i| quote_filter_string(i)).collect();
    format!("id NOT IN ({})", quoted.join(", "))
}

/// Row timestamp in Unix nanoseconds: the int64 `data.timestamp` when present, else the
/// RFC 3339 envelope timestamp.
pub(crate) fn row_nanos(row: &Value) -> i64 {
    let data = row.get("data").unwrap_or(&Value::Null);
    if let Some(n) = num_of(data, "timestamp").filter(|n| *n > 0) {
        return n;
    }
    let mut ts = str_of(row, "timestamp");
    if ts.is_empty() {
        ts = str_of(data, "timestamp");
    }
    humantime::parse_rfc3339_weak(ts.trim())
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_nanos() as i64)
        .unwrap_or(0)
}

pub(crate) fn row_millis(row: &Value) -> i64 {
    row_nanos(row) / 1_000_000
}

fn lower_opt(s: String) -> Option<String> {
    (!s.is_empty()).then(|| s.to_ascii_lowercase())
}

/// Maps a raw logs row (query or live tail) to a [`LogRecord`].
pub(crate) fn log_record_from_row(row: &Value) -> LogRecord {
    let d = row.get("data").unwrap_or(&Value::Null);
    let text = str_of(d, "severity_text");
    let severity = match num_of(d, "severity_number").filter(|n| *n > 0) {
        Some(n) => Severity::from_number(n.clamp(0, 255) as u8),
        None => text.parse().unwrap_or_default(),
    };
    let trace_id = lower_opt(str_of(d, "trace_id"));
    let trace_flags = trace_id
        .as_ref()
        .map(|_| (num_of(d, "trace_flags").unwrap_or(0) & 0xff) as u8);
    LogRecord {
        timestamp: UNIX_EPOCH + Duration::from_nanos(row_nanos(row).max(0) as u64),
        observed_timestamp: None,
        severity,
        severity_text: if text.is_empty() {
            severity.text().to_owned()
        } else {
            text
        },
        body: str_of(d, "body"),
        attributes: attrs_of(&[
            d.get("attributes_string"),
            d.get("attributes_number"),
            d.get("attributes_bool"),
        ]),
        resource: attrs_of(&[d.get("resources_string")]),
        trace_id,
        span_id: lower_opt(str_of(d, "span_id")),
        trace_flags,
    }
}

/// One log field known to SigNoz. A name may exist in several contexts (for example an
/// attribute and a resource attribute).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogField {
    /// Field name, for example `service.name` or `body`.
    pub name: String,
    /// `log`, `resource`, `attribute` or `scope`.
    pub context: String,
    /// `string`, `number` or `bool`.
    pub data_type: String,
    /// The server's description, when any.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

/// Narrows [`SignozQueryClient::list_log_fields`]. The default lists every field.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ListFieldsOptions {
    /// Keeps fields whose name contains this text.
    pub search_text: Option<String>,
    /// Caps the number of names (0 = server default).
    pub limit: usize,
}

/// Narrows [`SignozQueryClient::list_field_values`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FieldValuesOptions {
    /// Signal (default logs).
    pub signal: Signal,
    /// Keeps values containing this text.
    pub search_text: Option<String>,
}

impl SignozQueryClient {
    /// Runs a raw logs query (`POST /api/v5/query_range`) and returns one page of normalized
    /// records, ordered by timestamp then id.
    ///
    /// Paging uses the SDK's own cursor, not the server's `nextCursor` (which skips the other
    /// rows of the boundary millisecond): the next page narrows the window to the boundary
    /// millisecond and excludes the ids already returned there.
    pub async fn query_logs(&self, query: LogQuery) -> Result<LogQueryResult> {
        let op = "queryLogs";
        let limit = check_limit(op, query.limit, DEFAULT_SEARCH_LIMIT)?;
        let mut order = query.order;
        let (mut start, mut end) = TimeRange {
            start: query.start,
            end: query.end,
        }
        .resolve();
        let mut filter = query.filter.clone();
        let mut prev: Option<LogCursor> = None;
        if let Some(cursor) = query.cursor.as_deref().filter(|c| !c.is_empty()) {
            let (c, o) = decode_cursor(op, cursor)?;
            order = o;
            start = from_millis(c.s);
            end = from_millis(c.e);
            if order == SortOrder::Desc {
                end = from_millis(c.m + 1);
            } else {
                start = from_millis(c.m);
            }
            filter = and_filters([
                filter.as_str(),
                not_in_ids(c.x.as_deref().unwrap_or_default()).as_str(),
            ]);
            prev = Some(c);
        }
        let dir = order_str(order);
        let mut spec = Map::new();
        spec.insert("signal".into(), json!("logs"));
        if let Some(f) = filter_spec(&filter) {
            spec.insert("filter".into(), f);
        }
        spec.insert(
            "order".into(),
            json!([
                {"key": {"name": "timestamp"}, "direction": dir},
                {"key": {"name": "id"}, "direction": dir},
            ]),
        );
        spec.insert("limit".into(), json!(limit));
        if !query.select_fields.is_empty() {
            let fields: Vec<Value> = query
                .select_fields
                .iter()
                .map(|f| json!({ "name": f }))
                .collect();
            spec.insert("selectFields".into(), Value::Array(fields));
        }
        let (raw, warnings) = self.builder_query(op, "raw", start, end, spec).await?;
        let rows = rows_of(raw.as_ref());
        let mut out = LogQueryResult {
            records: rows.iter().map(log_record_from_row).collect(),
            next_cursor: None,
            warnings,
        };
        if let Some(last) = rows.last().filter(|_| rows.len() >= limit) {
            let (s, e) = match &prev {
                Some(p) => (p.s, p.e),
                None => (millis(start), millis(end)),
            };
            let ms = row_millis(last);
            let mut seen = BTreeSet::new();
            let mut ids = Vec::new();
            if let Some(p) = prev.as_ref().filter(|p| p.m == ms) {
                for id in p.x.iter().flatten() {
                    if seen.insert(id.clone()) {
                        ids.push(id.clone());
                    }
                }
            }
            for row in rows.iter().filter(|r| row_millis(r) == ms) {
                let id = row.get("data").map(|d| str_of(d, "id")).unwrap_or_default();
                if !id.is_empty() && seen.insert(id.clone()) {
                    ids.push(id);
                }
            }
            out.next_cursor = Some(encode_cursor(&LogCursor {
                v: 1,
                s,
                e,
                o: dir.to_owned(),
                m: ms,
                x: Some(ids),
            }));
        }
        Ok(out)
    }

    /// Lists the log fields (`GET /api/v1/fields/keys?signal=logs`), sorted by name and
    /// context.
    pub async fn list_log_fields(&self, opts: ListFieldsOptions) -> Result<Vec<LogField>> {
        let op = "listLogFields";
        let mut params = vec![("signal", "logs".to_owned())];
        if let Some(s) = opts.search_text.filter(|s| !s.is_empty()) {
            params.push(("searchText", s));
        }
        if opts.limit > 0 {
            params.push(("limit", opts.limit.to_string()));
        }
        let data = self
            .call(op, Method::GET, "/api/v1/fields/keys", &params, None, true)
            .await?;
        let mut out = Vec::new();
        if let Some(keys) = data.get("keys").and_then(Value::as_object) {
            for (name, variants) in keys {
                for v in variants.as_array().into_iter().flatten() {
                    let n = str_of(v, "name");
                    let description = str_of(v, "description");
                    out.push(LogField {
                        name: if n.is_empty() { name.clone() } else { n },
                        context: str_of(v, "fieldContext"),
                        data_type: str_of(v, "fieldDataType"),
                        description: (!description.is_empty()).then_some(description),
                    });
                }
            }
        }
        out.sort_by(|a, b| a.name.cmp(&b.name).then_with(|| a.context.cmp(&b.context)));
        Ok(out)
    }

    /// Returns the distinct values of a field (`GET /api/v1/fields/values`): strings, numbers
    /// and booleans, sorted by their text form.
    pub async fn list_field_values(
        &self,
        name: &str,
        opts: FieldValuesOptions,
    ) -> Result<Vec<AttrValue>> {
        let op = "listFieldValues";
        validate_field(name)?;
        let mut params = vec![
            ("signal", opts.signal.as_str().to_owned()),
            ("name", name.to_owned()),
        ];
        if let Some(s) = opts.search_text.filter(|s| !s.is_empty()) {
            params.push(("searchText", s));
        }
        let data = self
            .call(
                op,
                Method::GET,
                "/api/v1/fields/values",
                &params,
                None,
                true,
            )
            .await?;
        let mut seen = BTreeSet::new();
        let mut out = Vec::new();
        for group in ["stringValues", "numberValues", "boolValues"] {
            let values = data.pointer(&format!("/values/{group}"));
            for v in values.and_then(Value::as_array).into_iter().flatten() {
                if !(v.is_string() || v.is_number() || v.is_boolean()) {
                    continue;
                }
                let key = format!("{}:{v}", group);
                if seen.insert(key) {
                    out.push(v.clone());
                }
            }
        }
        out.sort_by_key(text_of);
        Ok(out)
    }

    /// The sorted unique log field names (`LogQueryClient::list_labels`).
    pub async fn list_labels(&self) -> Result<Vec<String>> {
        let fields = self.list_log_fields(ListFieldsOptions::default()).await?;
        let mut out: Vec<String> = fields.into_iter().map(|f| f.name).collect();
        out.dedup();
        Ok(out)
    }

    /// SigNoz has no streams; the service names that sent logs are the stream equivalent
    /// (sorted).
    pub async fn list_streams(&self) -> Result<Vec<String>> {
        let values = self
            .list_field_values("service.name", FieldValuesOptions::default())
            .await?;
        let mut out: Vec<String> = values
            .into_iter()
            .filter_map(|v| v.as_str().map(str::to_owned))
            .collect();
        out.sort();
        Ok(out)
    }

    /// `LogQueryClient::search`: `query` is a SigNoz filter expression (bare words search the
    /// body).
    pub async fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        let page = self
            .query_logs(LogQuery {
                filter: query.to_owned(),
                start: opts.start,
                end: opts.end,
                limit: opts.limit,
                order: opts.order,
                cursor: opts.cursor,
                select_fields: Vec::new(),
            })
            .await?;
        Ok(LogSearchResult {
            records: page.records,
            next_cursor: page.next_cursor,
            total: None,
        })
    }
}

pub(crate) fn rows_of(raw: Option<&Value>) -> Vec<Value> {
    raw.and_then(|r| r.get("rows"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

#[async_trait]
impl LogQueryClient for SignozQueryClient {
    async fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        SignozQueryClient::search(self, query, opts).await
    }

    async fn tail(
        &self,
        query: &str,
        on_record: RecordCallback,
        opts: TailOptions,
    ) -> Result<TailHandle> {
        SignozQueryClient::tail(self, query, on_record, opts).await
    }

    async fn list_streams(&self) -> Result<Vec<String>> {
        SignozQueryClient::list_streams(self).await
    }

    async fn list_labels(&self) -> Result<Vec<String>> {
        SignozQueryClient::list_labels(self).await
    }
}

//! Traces queries: spans (`query_traces`), the waterfall (`get_trace`), trace search,
//! services, operations, dependencies, and the [`TraceQueryClient`] implementation.

use std::collections::BTreeSet;
use std::time::SystemTime;

use async_trait::async_trait;
use futures::stream::{self, StreamExt, TryStreamExt};
use serde::Serialize;
use serde_json::{json, Map, Value};

use crate::aggregate::{AggregateQuery, Aggregation};
use crate::core::{ErrorCode, Method, Result};
use crate::filter::{and_filters, quote_filter_string, FilterBuilder};
use crate::logging::SortOrder;
use crate::query::{
    attrs_of, check_limit, filter_spec, from_millis, nanos, num_of, str_of, text_of, Signal,
    SignozQueryClient, TimeRange, DEFAULT_SEARCH_LIMIT, DEFAULT_TRACE_SEARCH_LIMIT,
    MAX_QUERY_LIMIT,
};
use crate::query_logs::{row_nanos, rows_of};
use crate::tracing::{
    normalize_trace_id, sort_spans, DependencyLink, DependencyOptions, Span, SpanEvent, SpanKind,
    SpanLink, SpanStatus, StatusCode, Trace, TraceQueryClient, TraceSearchQuery, TraceSummary,
};

/// Parallel waterfall calls of `search_traces`.
const SEARCH_CONCURRENCY: usize = 4;

/// A spans query for [`SignozQueryClient::query_traces`]. The default lists the newest 100
/// spans of the last hour.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TraceQuery {
    /// Keeps spans of this `service.name`.
    pub service: Option<String>,
    /// Keeps spans with this name.
    pub operation: Option<String>,
    /// Extra SigNoz filter expression, for example
    /// `duration_nano >= 120000000 AND http.method = 'GET'`.
    pub filter: String,
    /// Window start (default end − 1 h).
    pub start: Option<SystemTime>,
    /// Window end (default now).
    pub end: Option<SystemTime>,
    /// Caps the number of spans (0 = default 100, at most
    /// [`MAX_QUERY_LIMIT`](crate::MAX_QUERY_LIMIT)).
    pub limit: usize,
    /// Newest first (default) or oldest first.
    pub order: SortOrder,
    /// Skips that many spans (offset paging over a fixed window).
    pub offset: usize,
}

impl TraceQuery {
    /// Spans of `service`.
    pub fn service(service: impl Into<String>) -> Self {
        TraceQuery {
            service: Some(service.into()),
            ..TraceQuery::default()
        }
    }
    /// Keeps spans named `operation`.
    pub fn operation(mut self, operation: impl Into<String>) -> Self {
        self.operation = Some(operation.into());
        self
    }
    /// Sets the extra filter expression.
    pub fn filter(mut self, filter: impl Into<String>) -> Self {
        self.filter = filter.into();
        self
    }
    /// Sets the time window.
    pub fn range(mut self, start: Option<SystemTime>, end: Option<SystemTime>) -> Self {
        self.start = start;
        self.end = end;
        self
    }
    /// Sets the limit.
    pub fn limit(mut self, limit: usize) -> Self {
        self.limit = limit;
        self
    }

    fn expression(&self) -> String {
        let mut f = FilterBuilder::new();
        if let Some(s) = self.service.as_deref().filter(|s| !s.is_empty()) {
            f = f.eq("service.name", s);
        }
        if let Some(o) = self.operation.as_deref().filter(|s| !s.is_empty()) {
            f = f.eq("name", o);
        }
        and_filters([f.expression(), self.filter.clone()])
    }
}

/// Result of [`SignozQueryClient::query_traces`]: one [`Span`] per matching span.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct SpanQueryResult {
    /// Matching spans.
    pub spans: Vec<Span>,
    /// Server warnings (for example unknown fields in the filter).
    pub warnings: Vec<String>,
}

fn opt(s: String) -> Option<String> {
    (!s.is_empty()).then_some(s)
}

/// Maps `status_code` (0/1/2, omitted for Unset) and its string form.
fn span_status(d: &Value) -> SpanStatus {
    let code = num_of(d, "status_code").unwrap_or_else(|| {
        match str_of(d, "status_code_string")
            .to_ascii_lowercase()
            .as_str()
        {
            "ok" => 1,
            "error" => 2,
            _ => 0,
        }
    });
    SpanStatus {
        code: match code {
            1 => StatusCode::Ok,
            2 => StatusCode::Error,
            _ => StatusCode::Unset,
        },
        message: opt(str_of(d, "status_message")),
    }
}

fn span_base(d: &Value) -> Span {
    let resource = d.get("resource").unwrap_or(&Value::Null);
    let kind = SpanKind::parse(&str_of(d, "kind_string"));
    let duration = (num_of(d, "duration_nano").unwrap_or(0).max(0) / 1000) as u64;
    Span {
        trace_id: str_of(d, "trace_id").to_ascii_lowercase(),
        span_id: str_of(d, "span_id").to_ascii_lowercase(),
        parent_span_id: opt(str_of(d, "parent_span_id").to_ascii_lowercase()),
        name: str_of(d, "name"),
        kind,
        service_name: str_of(resource, "service.name"),
        start_micros: 0,
        end_micros: 0,
        duration_micros: duration,
        status: span_status(d),
        attributes: attrs_of(&[d.get("attributes")]),
        events: Vec::new(),
        links: Vec::new(),
    }
}

fn events_of(d: &Value, attr_key: &str) -> Vec<SpanEvent> {
    d.get("events")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter(|e| e.is_object())
        .map(|e| SpanEvent {
            name: str_of(e, "name"),
            time_micros: (num_of(e, "timeUnixNano").unwrap_or(0).max(0) / 1000) as u64,
            attributes: attrs_of(&[e.get(attr_key)]),
        })
        .collect()
}

/// Maps a raw spans row. Start is the RFC 3339 ns row timestamp. `links` mirror the span
/// references: the parent reference (and a root's trace-only reference) are dropped.
pub(crate) fn span_from_raw(row: &Value) -> Span {
    let d = row.get("data").unwrap_or(&Value::Null);
    let mut s = span_base(d);
    s.start_micros = (row_nanos(row).max(0) / 1000) as u64;
    s.end_micros = s.start_micros + s.duration_micros;
    s.events = events_of(d, "attributes");
    for l in d
        .get("links")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let sid = str_of(l, "spanId").to_ascii_lowercase();
        if sid.is_empty() || Some(&sid) == s.parent_span_id.as_ref() {
            continue;
        }
        s.links.push(SpanLink {
            trace_id: str_of(l, "traceId").to_ascii_lowercase(),
            span_id: sid,
            attributes: Default::default(),
        });
    }
    s
}

/// Maps a waterfall span (`time_unix` is milliseconds; links are the `FOLLOWS_FROM`
/// references; SigNoz drops link attributes).
pub(crate) fn span_from_waterfall(d: &Value) -> Span {
    let mut s = span_base(d);
    s.start_micros = (num_of(d, "time_unix").unwrap_or(0).max(0) as u64) * 1000;
    s.end_micros = s.start_micros + s.duration_micros;
    s.events = events_of(d, "attributeMap");
    for r in d
        .get("references")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let sid = str_of(r, "spanId");
        if !str_of(r, "refType").eq_ignore_ascii_case("FOLLOWS_FROM") || sid.is_empty() {
            continue;
        }
        s.links.push(SpanLink {
            trace_id: str_of(r, "traceId").to_ascii_lowercase(),
            span_id: sid.to_ascii_lowercase(),
            attributes: Default::default(),
        });
    }
    s
}

impl SignozQueryClient {
    /// Runs a raw spans query (`POST /api/v5/query_range`, signal traces) and returns one
    /// normalized [`Span`] per matching span.
    pub async fn query_traces(&self, query: TraceQuery) -> Result<SpanQueryResult> {
        let op = "queryTraces";
        let limit = check_limit(op, query.limit, DEFAULT_SEARCH_LIMIT)?;
        let (start, end) = TimeRange {
            start: query.start,
            end: query.end,
        }
        .resolve();
        let dir = match query.order {
            SortOrder::Asc => "asc",
            SortOrder::Desc => "desc",
        };
        let mut spec = Map::new();
        spec.insert("signal".into(), json!("traces"));
        if let Some(f) = filter_spec(&query.expression()) {
            spec.insert("filter".into(), f);
        }
        spec.insert(
            "order".into(),
            json!([{"key": {"name": "timestamp"}, "direction": dir}]),
        );
        spec.insert("limit".into(), json!(limit));
        if query.offset > 0 {
            spec.insert("offset".into(), json!(query.offset));
        }
        let (raw, warnings) = self.builder_query(op, "raw", start, end, spec).await?;
        Ok(SpanQueryResult {
            spans: rows_of(raw.as_ref()).iter().map(span_from_raw).collect(),
            warnings,
        })
    }

    async fn waterfall(&self, op: &str, trace_id: &str) -> Result<Option<Value>> {
        let path = format!("/api/v4/traces/{trace_id}/waterfall");
        match self
            .call(op, Method::POST, &path, &[], Some(&json!({})), true)
            .await
        {
            Ok(v) => Ok(Some(v)),
            Err(e) if e.code() == ErrorCode::NotFound => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// The trace with every span (`POST /api/v4/traces/{id}/waterfall`), or `None` when it
    /// does not exist. Span start times have millisecond precision. A trace larger than the
    /// server's waterfall window is completed with raw span queries.
    pub async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        let op = "getTrace";
        let id = normalize_trace_id(trace_id)?;
        let Some(w) = self.waterfall(op, &id).await? else {
            return Ok(None);
        };
        let spans: Vec<Value> = w
            .get("spans")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        if spans.is_empty() {
            return Ok(None);
        }
        let total = num_of(&w, "totalSpansCount").unwrap_or(0);
        let has_more = w.get("hasMore").and_then(Value::as_bool).unwrap_or(false);
        let mut out = if has_more || total > spans.len() as i64 {
            let start = from_millis(num_of(&w, "startTimestampMillis").unwrap_or(0));
            let end = from_millis(num_of(&w, "endTimestampMillis").unwrap_or(0) + 1);
            self.all_spans(&id, start, end).await?
        } else {
            spans.iter().map(span_from_waterfall).collect()
        };
        sort_spans(&mut out);
        Ok(Some(Trace {
            trace_id: id,
            spans: out,
        }))
    }

    /// Pages through every span of a trace with a frozen window.
    async fn all_spans(&self, id: &str, start: SystemTime, end: SystemTime) -> Result<Vec<Span>> {
        let mut out = Vec::new();
        loop {
            let page = self
                .query_traces(TraceQuery {
                    filter: format!("trace_id = {}", quote_filter_string(id)),
                    start: Some(start),
                    end: Some(end),
                    limit: MAX_QUERY_LIMIT,
                    order: SortOrder::Asc,
                    offset: out.len(),
                    ..TraceQuery::default()
                })
                .await?;
            let n = page.spans.len();
            out.extend(page.spans);
            if n < MAX_QUERY_LIMIT {
                return Ok(out);
            }
        }
    }

    /// Finds traces (request type `trace`), newest first, at most `query.limit` (default 20):
    /// a trace matches when any of its spans matches service, operation, tags and the duration
    /// bounds. `start_micros`, `span_count` (all spans of the trace), `root_service_name` and
    /// `root_name` come from each hit's waterfall (millisecond precision); `duration_micros`
    /// is the root span's.
    pub async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        let op = "searchTraces";
        let limit = check_limit(op, query.limit, DEFAULT_TRACE_SEARCH_LIMIT)?;
        let mut f = FilterBuilder::new();
        if let Some(s) = query.service.as_deref().filter(|s| !s.is_empty()) {
            f = f.eq("service.name", s);
        }
        if let Some(o) = query.operation.as_deref().filter(|s| !s.is_empty()) {
            f = f.eq("name", o);
        }
        for (k, v) in &query.tags {
            f = f.eq(k, v.as_str());
        }
        if let Some(d) = query.min_duration.filter(|d| !d.is_zero()) {
            f = f.gte("duration_nano", d.as_nanos() as u64);
        }
        if let Some(d) = query.max_duration.filter(|d| !d.is_zero()) {
            f = f.lte("duration_nano", d.as_nanos() as u64);
        }
        let expr = f.build()?;
        let (start, end) = TimeRange {
            start: query.start,
            end: query.end,
        }
        .resolve();
        let mut spec = Map::new();
        spec.insert("signal".into(), json!("traces"));
        if let Some(fs) = filter_spec(&expr) {
            spec.insert("filter".into(), fs);
        }
        spec.insert(
            "order".into(),
            json!([{"key": {"name": "timestamp"}, "direction": "desc"}]),
        );
        spec.insert("limit".into(), json!(limit));
        let (raw, _) = self.builder_query(op, "trace", start, end, spec).await?;
        let summaries: Vec<TraceSummary> = rows_of(raw.as_ref())
            .iter()
            .filter_map(|row| {
                let d = row.get("data")?;
                let id = normalize_trace_id(&str_of(d, "trace_id")).ok()?;
                Some(TraceSummary {
                    trace_id: id,
                    root_service_name: opt(str_of(d, "service.name")),
                    root_name: opt(str_of(d, "name")),
                    start_micros: 0,
                    duration_micros: (num_of(d, "duration_nano").unwrap_or(0).max(0) / 1000) as u64,
                    span_count: num_of(d, "span_count").unwrap_or(0).max(0) as usize,
                })
            })
            .collect();
        stream::iter(summaries)
            .map(|mut s| async move {
                if let Some(w) = self.waterfall(op, &s.trace_id).await? {
                    s.start_micros =
                        (num_of(&w, "startTimestampMillis").unwrap_or(0).max(0) as u64) * 1000;
                    if let Some(n) = num_of(&w, "totalSpansCount").filter(|n| *n > 0) {
                        s.span_count = n as usize;
                    }
                    if let Some(name) = opt(str_of(&w, "rootServiceName")) {
                        s.root_service_name = Some(name);
                    }
                    if let Some(entry) = opt(str_of(&w, "rootServiceEntryPoint")) {
                        s.root_name = Some(entry);
                    }
                }
                Ok::<_, crate::Error>(s)
            })
            .buffered(SEARCH_CONCURRENCY)
            .try_collect()
            .await
    }

    /// `count()` grouped by `field`; returns the sorted unique group values.
    async fn group_names(
        &self,
        field: &str,
        filter: String,
        range: TimeRange,
    ) -> Result<Vec<String>> {
        let res = self
            .aggregate(AggregateQuery {
                signal: Signal::Traces,
                filter,
                aggregations: vec![Aggregation::new("count()", "count")],
                group_by: vec![field.to_owned()],
                start: range.start,
                end: range.end,
                limit: MAX_QUERY_LIMIT,
                ..AggregateQuery::default()
            })
            .await?;
        let set: BTreeSet<String> = res
            .rows
            .iter()
            .filter_map(|r| r.groups.get(field))
            .map(text_of)
            .filter(|s| !s.is_empty())
            .collect();
        Ok(set.into_iter().collect())
    }

    /// Every service with spans in the last hour, sorted.
    pub async fn list_services(&self) -> Result<Vec<String>> {
        self.list_services_in_range(TimeRange::default()).await
    }

    /// Every service with spans in `range` (`count()` grouped by `service.name`), sorted.
    pub async fn list_services_in_range(&self, range: TimeRange) -> Result<Vec<String>> {
        self.group_names("service.name", String::new(), range).await
    }

    /// The span names of `service` in the last hour, sorted.
    pub async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.list_operations_in_range(service, TimeRange::default())
            .await
    }

    /// The span names of `service` in `range`, sorted.
    pub async fn list_operations_in_range(
        &self,
        service: &str,
        range: TimeRange,
    ) -> Result<Vec<String>> {
        if service.trim().is_empty() {
            return Err(crate::Error::validation(
                "listOperations: service is required",
            ));
        }
        let filter = FilterBuilder::new()
            .eq("service.name", service)
            .expression();
        self.group_names("name", filter, range).await
    }

    /// The service dependency graph in the window (`POST /api/v1/dependency_graph`; default
    /// the last hour), sorted by parent, child. Edges come from client→server spans and from
    /// client spans with `db.system` or `messaging.system`.
    pub async fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        let op = "getDependencies";
        let (start, end) = TimeRange {
            start: opts.start,
            end: opts.end,
        }
        .resolve();
        let body = json!({
            "start": nanos(start).to_string(),
            "end": nanos(end).to_string(),
            "tags": [],
        });
        let raw = self
            .call(
                op,
                Method::POST,
                "/api/v1/dependency_graph",
                &[],
                Some(&body),
                false,
            )
            .await?;
        let mut out: Vec<DependencyLink> = raw
            .as_array()
            .into_iter()
            .flatten()
            .map(|e| DependencyLink {
                parent: str_of(e, "parent"),
                child: str_of(e, "child"),
                call_count: num_of(e, "callCount").unwrap_or(0).max(0) as u64,
            })
            .collect();
        out.sort_by(|a, b| a.parent.cmp(&b.parent).then_with(|| a.child.cmp(&b.child)));
        Ok(out)
    }
}

#[async_trait]
impl TraceQueryClient for SignozQueryClient {
    async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        SignozQueryClient::get_trace(self, trace_id).await
    }

    async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        SignozQueryClient::search_traces(self, query).await
    }

    async fn list_services(&self) -> Result<Vec<String>> {
        SignozQueryClient::list_services(self).await
    }

    async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        SignozQueryClient::list_operations(self, service).await
    }

    async fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        SignozQueryClient::get_dependencies(self, opts).await
    }
}

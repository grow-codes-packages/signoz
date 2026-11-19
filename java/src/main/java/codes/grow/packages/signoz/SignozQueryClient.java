package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.HttpTransport;
import codes.grow.packages.signoz.core.Json;
import codes.grow.packages.signoz.core.NotFoundException;
import codes.grow.packages.signoz.core.TransportRequest;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.LogQueryClient;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.LogSearchResult;
import codes.grow.packages.signoz.logging.SearchOptions;
import codes.grow.packages.signoz.logging.SortOrder;
import codes.grow.packages.signoz.logging.TailHandle;
import codes.grow.packages.signoz.logging.TailOptions;
import codes.grow.packages.signoz.tracing.query.DependencyLink;
import codes.grow.packages.signoz.tracing.query.Span;
import codes.grow.packages.signoz.tracing.query.Trace;
import codes.grow.packages.signoz.tracing.query.TraceIds;
import codes.grow.packages.signoz.tracing.query.TraceQueryClient;
import codes.grow.packages.signoz.tracing.query.TraceSearchQuery;
import codes.grow.packages.signoz.tracing.query.TraceSummary;
import codes.grow.packages.signoz.tracing.query.Traces;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Query client of the SigNoz query API ({@code SIGNOZ-API-KEY} auth): logs and spans through {@code
 * POST /api/v5/query_range}, traces through the waterfall API, services and operations through v5
 * aggregations, dependencies through {@code /api/v1/dependency_graph}, log fields through {@code
 * /api/v1/fields/*} and live tail through {@code /api/v3/logs/livetail}. It implements the category
 * interfaces {@link LogQueryClient} and {@link TraceQueryClient}. Get it from {@link
 * SignozClient#query()}; it is thread-safe.
 *
 * <p>Every method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}.
 */
public final class SignozQueryClient implements LogQueryClient, TraceQueryClient {
  /** Window of a query without an explicit start: the hour before the end (end defaults to now). */
  public static final Duration DEFAULT_QUERY_WINDOW = Duration.ofHours(1);

  /** Default page size of raw queries and default group limit of aggregations. */
  public static final int DEFAULT_LIMIT = 100;

  /** Largest row limit the SigNoz query API accepts. */
  public static final int MAX_QUERY_LIMIT = 10_000;

  /** Parallel waterfall calls of {@link #searchTraces}. */
  static final int SEARCH_CONCURRENCY = 4;

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final SignozApi api;
  private final Clock clock;
  private final Set<LiveTail> tails = java.util.concurrent.ConcurrentHashMap.newKeySet();

  SignozQueryClient(SignozApi api, Clock clock) {
    this.api = api;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- helpers

  private static <T> T await(CompletableFuture<T> f, String op) {
    return HttpTransport.await(f, op);
  }

  /** Resolved query window in epoch milliseconds. */
  record Window(long startMs, long endMs) {}

  Window window(Instant start, Instant end) {
    Instant e = end != null ? end : clock.instant();
    Instant s = start != null ? start : e.minus(DEFAULT_QUERY_WINDOW);
    return new Window(s.toEpochMilli(), e.toEpochMilli());
  }

  static int checkLimit(String op, int limit, int def) {
    if (limit == 0) {
      return def;
    }
    if (limit < 0 || limit > MAX_QUERY_LIMIT) {
      throw new ValidationException(op + ": limit must be between 1 and " + MAX_QUERY_LIMIT);
    }
    return limit;
  }

  private static Map<String, Object> field(String name) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    return m;
  }

  private static Map<String, Object> order(String key, SortOrder dir) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", field(key));
    m.put("direction", dir.wireValue());
    return m;
  }

  private static Map<String, Object> spec(Signal signal, String filter) {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("name", "A");
    spec.put("signal", signal.wireValue());
    if (filter != null && !filter.isBlank()) {
      spec.put("filter", Map.of("expression", filter));
    }
    return spec;
  }

  /** Result of one {@code query_range} call: the first result (may be null) and the warnings. */
  record Range(JsonNode result, List<String> warnings) {}

  CompletableFuture<Range> queryRange(
      String op, String requestType, Window w, Map<String, Object> spec) {
    if (w.endMs() <= w.startMs()) {
      throw new ValidationException(op + ": start must be before end");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("start", w.startMs());
    body.put("end", w.endMs());
    body.put("requestType", requestType);
    Map<String, Object> query = new LinkedHashMap<>();
    query.put("type", "builder_query");
    query.put("spec", spec);
    body.put("compositeQuery", Map.of("queries", List.of(query)));
    return api.call(op, TransportRequest.post("/api/v5/query_range").json(body), true)
        .thenApply(
            data -> {
              List<String> warnings = warnings(op, data);
              JsonNode results = data.path("data").path("results");
              JsonNode first = results.isArray() && results.size() > 0 ? results.get(0) : null;
              return new Range(first, warnings);
            });
  }

  /** Redacted warning messages of a query_range response (also logged). */
  private List<String> warnings(String op, JsonNode data) {
    List<String> warnings = new ArrayList<>();
    JsonNode warning = data.path("warning");
    if (warning.isObject()) {
      for (JsonNode w2 : warning.path("warnings")) {
        String m = Rows.str(w2, "message");
        if (!m.isEmpty()) {
          warnings.add(api.redact(m));
        }
      }
      String m = Rows.str(warning, "message");
      if (warnings.isEmpty() && !m.isEmpty()) {
        warnings.add(api.redact(m));
      }
      for (String w3 : warnings) {
        api.warn("signoz query warning", Map.of("operation", op, "warning", w3));
      }
    }
    return warnings;
  }

  /**
   * Posts a raw request body to {@code POST /api/v5/query_range} and returns the envelope's {@code
   * data} plus the server warnings. It is the escape hatch for queries the typed helpers do not
   * cover (formulas, PromQL, ClickHouse SQL, several queries at once).
   *
   * @param body the request body (a {@code Map}, record or {@code JsonNode}, encoded as JSON)
   * @return data and warnings
   */
  public QueryRangeResult queryRange(Object body) {
    return await(queryRangeAsync(body), "queryRange");
  }

  /**
   * Asynchronous {@link #queryRange(Object)}.
   *
   * @param body the request body
   * @return a future result
   */
  public CompletableFuture<QueryRangeResult> queryRangeAsync(Object body) {
    return SignozApi.defer(
        () ->
            api.call("queryRange", TransportRequest.post("/api/v5/query_range").json(body), true)
                .thenApply(data -> new QueryRangeResult(data, warnings("queryRange", data))));
  }

  private static List<JsonNode> rows(JsonNode result) {
    List<JsonNode> out = new ArrayList<>();
    if (result != null) {
      for (JsonNode r : result.path("rows")) {
        out.add(r);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- logs

  /** The SDK's own keyset cursor (the server cursor skips rows of the boundary millisecond). */
  record LogCursor(long start, long end, SortOrder order, long ms, List<String> ids) {
    String encode() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("v", 1);
      m.put("s", start);
      m.put("e", end);
      m.put("o", order.wireValue());
      m.put("m", ms);
      m.put("x", ids);
      return B64.encodeToString(Json.encode(m));
    }

    static LogCursor decode(String op, String text) {
      try {
        JsonNode n = Json.tree(Base64.getUrlDecoder().decode(text));
        String o = n.path("o").asText("");
        if (n.path("v").asInt() != 1
            || !n.path("s").isIntegralNumber()
            || !n.path("e").isIntegralNumber()
            || !n.path("m").isIntegralNumber()
            || !(o.equals("asc") || o.equals("desc"))
            || !(n.path("x").isArray() || n.path("x").isNull() || n.path("x").isMissingNode())) {
          throw new ValidationException(op + ": invalid cursor");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode x : n.path("x")) {
          if (!x.isTextual()) {
            throw new ValidationException(op + ": invalid cursor");
          }
          ids.add(x.asText());
        }
        return new LogCursor(
            n.get("s").asLong(),
            n.get("e").asLong(),
            o.equals("asc") ? SortOrder.ASC : SortOrder.DESC,
            n.get("m").asLong(),
            ids);
      } catch (IllegalArgumentException | ValidationException e) {
        throw new ValidationException(op + ": invalid cursor");
      }
    }
  }

  static String notInIds(List<String> ids) {
    if (ids.isEmpty()) {
      return "";
    }
    List<String> q = new ArrayList<>();
    for (String id : ids) {
      q.add(FilterBuilder.quoteFilterString(id));
    }
    return "id NOT IN (" + String.join(", ", q) + ")";
  }

  /**
   * Runs a raw logs query and returns one page of normalized records, ordered by timestamp then id.
   *
   * <p>Paging uses the SDK's own cursor, not the server's {@code nextCursor} (which skips the other
   * rows of the boundary millisecond): the next page repeats the boundary millisecond and excludes
   * the ids already returned there.
   *
   * @param query the query
   * @return one page
   * @throws ValidationException for a bad limit, cursor, window or filter
   */
  public LogQueryResult queryLogs(LogQuery query) {
    return await(queryLogsAsync(query), "queryLogs");
  }

  /**
   * Asynchronous {@link #queryLogs(LogQuery)}.
   *
   * @param query the query
   * @return a future page
   */
  public CompletableFuture<LogQueryResult> queryLogsAsync(LogQuery query) {
    return SignozApi.defer(() -> queryLogsNow(query));
  }

  private CompletableFuture<LogQueryResult> queryLogsNow(LogQuery q) {
    String op = "queryLogs";
    int limit = checkLimit(op, q.limit(), DEFAULT_LIMIT);
    SortOrder order = q.order().orElse(SortOrder.DESC);
    Window w = window(q.start().orElse(null), q.end().orElse(null));
    String filter = q.filter();
    LogCursor prev = null;
    if (q.cursor().isPresent()) {
      prev = LogCursor.decode(op, q.cursor().get());
      order = prev.order();
      w =
          prev.order() == SortOrder.DESC
              ? new Window(prev.start(), prev.ms() + 1)
              : new Window(prev.ms(), prev.end());
      filter = FilterBuilder.andFilters(filter, notInIds(prev.ids()));
    }
    Map<String, Object> spec = spec(Signal.LOGS, filter);
    spec.put("order", List.of(order("timestamp", order), order("id", order)));
    spec.put("limit", limit);
    if (!q.selectFields().isEmpty()) {
      List<Object> fields = new ArrayList<>();
      for (String f : q.selectFields()) {
        fields.add(field(f));
      }
      spec.put("selectFields", fields);
    }
    final SortOrder ord = order;
    final Window win = w;
    final LogCursor previous = prev;
    return queryRange(op, "raw", w, spec)
        .thenApply(
            range -> {
              List<JsonNode> rows = rows(range.result());
              List<LogRecord> records = new ArrayList<>();
              for (JsonNode row : rows) {
                records.add(Rows.logRecord(row));
              }
              String next = null;
              if (!rows.isEmpty() && rows.size() >= limit) {
                long ms = Rows.rowMillis(rows.get(rows.size() - 1));
                Set<String> ids = new LinkedHashSet<>();
                if (previous != null && previous.ms() == ms) {
                  ids.addAll(previous.ids());
                }
                for (JsonNode row : rows) {
                  String id = Rows.str(row.path("data"), "id");
                  if (!id.isEmpty() && Rows.rowMillis(row) == ms) {
                    ids.add(id);
                  }
                }
                long s = previous != null ? previous.start() : win.startMs();
                long e = previous != null ? previous.end() : win.endMs();
                next = new LogCursor(s, e, ord, ms, List.copyOf(ids)).encode();
              }
              return new LogQueryResult(records, Optional.ofNullable(next), range.warnings());
            });
  }

  /**
   * Searches log records ({@link LogQueryClient}): {@code query} is a SigNoz filter expression
   * (bare words search the body).
   *
   * @param query filter expression
   * @param options range, limit, order, cursor
   * @return one page
   */
  @Override
  public LogSearchResult search(String query, SearchOptions options) {
    return await(searchAsync(query, options), "logs.search");
  }

  @Override
  public CompletableFuture<LogSearchResult> searchAsync(String query, SearchOptions options) {
    SearchOptions o = options == null ? SearchOptions.defaults() : options;
    LogQuery q =
        LogQuery.builder()
            .filter(query)
            .start(o.start().orElse(null))
            .end(o.end().orElse(null))
            .limit(o.limit())
            .order(o.order())
            .cursor(o.cursor().orElse(null))
            .build();
    return queryLogsAsync(q)
        .thenApply(r -> new LogSearchResult(r.records(), r.nextCursor(), OptionalLong.empty()));
  }

  /**
   * Streams new log records matching {@code query} (a filter expression; empty = all) from the live
   * tail API ({@code GET /api/v3/logs/livetail}, server-sent events) and calls {@code onRecord} for
   * each, in arrival order, until the handle is stopped or the client is closed. The first
   * connection is made before this method returns, so a bad API key fails at once. A dropped stream
   * is reconnected after {@link TailOptions#interval()} from the last seen timestamp; records
   * already delivered are skipped by id. Reconnect errors go to {@link TailOptions#onError()}.
   *
   * @param query filter expression
   * @param onRecord callback per record
   * @param options start time (default now), reconnect interval, error callback
   * @return the running tail
   */
  @Override
  public TailHandle tail(String query, Consumer<LogRecord> onRecord, TailOptions options) {
    if (onRecord == null) {
      throw new ValidationException("logs.tail: onRecord is required");
    }
    TailOptions o = options == null ? TailOptions.defaults() : options;
    long startMs = o.start().orElse(clock.instant()).toEpochMilli();
    LiveTail t = new LiveTail(api, query, onRecord, o, startMs, tails::remove);
    t.start();
    tails.add(t);
    return t;
  }

  /** Stops every running tail (client close). */
  void stopTails() {
    for (LiveTail t : new ArrayList<>(tails)) {
      t.stop();
    }
  }

  /**
   * Lists the log fields ({@code GET /api/v1/fields/keys?signal=logs}), sorted by name and context.
   *
   * @param options search text and limit
   * @return fields
   */
  public List<LogField> listLogFields(ListFieldsOptions options) {
    return await(listLogFieldsAsync(options), "listLogFields");
  }

  /**
   * Lists every log field.
   *
   * @return fields
   */
  public List<LogField> listLogFields() {
    return listLogFields(ListFieldsOptions.defaults());
  }

  /**
   * Asynchronous {@link #listLogFields(ListFieldsOptions)}.
   *
   * @param options search text and limit
   * @return future fields
   */
  public CompletableFuture<List<LogField>> listLogFieldsAsync(ListFieldsOptions options) {
    ListFieldsOptions o = options == null ? ListFieldsOptions.defaults() : options;
    TransportRequest.Builder b =
        TransportRequest.get("/api/v1/fields/keys").query("signal", Signal.LOGS.wireValue());
    if (o.searchText() != null && !o.searchText().isEmpty()) {
      b.query("searchText", o.searchText());
    }
    if (o.limit() > 0) {
      b.query("limit", o.limit());
    }
    return api.call("listLogFields", b, true)
        .thenApply(
            data -> {
              List<LogField> out = new ArrayList<>();
              data.path("keys")
                  .properties()
                  .forEach(
                      e -> {
                        for (JsonNode v : e.getValue()) {
                          String name = Rows.str(v, "name");
                          String desc = Rows.str(v, "description");
                          out.add(
                              new LogField(
                                  name.isEmpty() ? e.getKey() : name,
                                  Rows.str(v, "fieldContext"),
                                  Rows.str(v, "fieldDataType"),
                                  desc.isEmpty() ? Optional.empty() : Optional.of(desc)));
                        }
                      });
              out.sort(Comparator.comparing(LogField::name).thenComparing(LogField::context));
              return out;
            });
  }

  /**
   * The sorted unique log field names ({@link LogQueryClient}).
   *
   * @return names
   */
  @Override
  public List<String> listLabels() {
    return await(listLabelsAsync(), "logs.listLabels");
  }

  @Override
  public CompletableFuture<List<String>> listLabelsAsync() {
    return listLogFieldsAsync(ListFieldsOptions.defaults())
        .thenApply(
            fields -> {
              Set<String> names = new TreeSet<>();
              fields.forEach(f -> names.add(f.name()));
              return List.copyOf(names);
            });
  }

  /**
   * SigNoz has no streams; the service names that sent logs are the stream equivalent (sorted).
   *
   * @return service names
   */
  @Override
  public List<String> listStreams() {
    return await(listStreamsAsync(), "logs.listStreams");
  }

  @Override
  public CompletableFuture<List<String>> listStreamsAsync() {
    return listFieldValuesAsync("service.name", FieldValuesOptions.defaults())
        .thenApply(
            values -> {
              Set<String> out = new TreeSet<>();
              for (Object v : values) {
                if (v instanceof String) {
                  out.add((String) v);
                }
              }
              return List.copyOf(out);
            });
  }

  /**
   * Returns the distinct values of a field ({@code GET /api/v1/fields/values}): strings, numbers
   * ({@code Long} / {@code Double}) and booleans, sorted by their text form.
   *
   * @param name field name
   * @param options signal and search text
   * @return values
   */
  public List<Object> listFieldValues(String name, FieldValuesOptions options) {
    return await(listFieldValuesAsync(name, options), "listFieldValues");
  }

  /**
   * Asynchronous {@link #listFieldValues}.
   *
   * @param name field name
   * @param options signal and search text
   * @return future values
   */
  public CompletableFuture<List<Object>> listFieldValuesAsync(
      String name, FieldValuesOptions options) {
    return SignozApi.defer(
        () -> {
          FilterBuilder.checkField(name);
          FieldValuesOptions o = options == null ? FieldValuesOptions.defaults() : options;
          TransportRequest.Builder b =
              TransportRequest.get("/api/v1/fields/values")
                  .query("signal", o.signal().wireValue())
                  .query("name", name);
          if (o.searchText() != null && !o.searchText().isEmpty()) {
            b.query("searchText", o.searchText());
          }
          return api.call("listFieldValues", b, true)
              .thenApply(
                  data -> {
                    Map<String, Object> seen = new LinkedHashMap<>();
                    JsonNode values = data.path("values");
                    for (String group :
                        new String[] {"stringValues", "numberValues", "boolValues"}) {
                      for (JsonNode v : values.path(group)) {
                        Object p = Rows.plain(v);
                        if (p instanceof String
                            || p instanceof Long
                            || p instanceof Double
                            || p instanceof Boolean) {
                          seen.putIfAbsent(p.getClass().getSimpleName() + ":" + p, p);
                        }
                      }
                    }
                    List<Object> out = new ArrayList<>(seen.values());
                    out.sort(Comparator.comparing(String::valueOf));
                    return out;
                  });
        });
  }

  // ---------------------------------------------------------------- spans and traces

  /**
   * Runs a raw spans query and returns one normalized span per matching span.
   *
   * @param query the query
   * @return the spans and warnings
   */
  public SpanQueryResult queryTraces(TraceQuery query) {
    return await(queryTracesAsync(query), "queryTraces");
  }

  /**
   * Asynchronous {@link #queryTraces(TraceQuery)}.
   *
   * @param query the query
   * @return future spans
   */
  public CompletableFuture<SpanQueryResult> queryTracesAsync(TraceQuery query) {
    return SignozApi.defer(
        () -> {
          String op = "queryTraces";
          int limit = checkLimit(op, query.limit(), DEFAULT_LIMIT);
          if (query.offset() < 0) {
            throw new ValidationException(op + ": offset must be >= 0");
          }
          Window w = window(query.start().orElse(null), query.end().orElse(null));
          Map<String, Object> spec = spec(Signal.TRACES, query.expression());
          spec.put("order", List.of(order("timestamp", query.order().orElse(SortOrder.DESC))));
          spec.put("limit", limit);
          if (query.offset() > 0) {
            spec.put("offset", query.offset());
          }
          return queryRange(op, "raw", w, spec)
              .thenApply(
                  range -> {
                    List<Span> spans = new ArrayList<>();
                    for (JsonNode row : rows(range.result())) {
                      spans.add(Rows.spanFromRaw(row));
                    }
                    return new SpanQueryResult(spans, range.warnings());
                  });
        });
  }

  private CompletableFuture<JsonNode> waterfall(String op, String traceId) {
    return api.call(
            op,
            TransportRequest.post("/api/v4/traces/" + traceId + "/waterfall").json(Map.of()),
            true)
        .handle(
            (data, err) -> {
              if (err == null) {
                return data;
              }
              Throwable c =
                  err instanceof CompletionException && err.getCause() != null
                      ? err.getCause()
                      : err;
              if (c instanceof NotFoundException) {
                return null;
              }
              throw err instanceof CompletionException
                  ? (CompletionException) err
                  : new CompletionException(err);
            });
  }

  /**
   * Returns the trace with every span ({@code POST /api/v4/traces/{id}/waterfall}), or empty when
   * it does not exist. Span start times have millisecond precision. A trace larger than the
   * server's waterfall window is completed with raw span queries.
   *
   * @param traceId trace id
   * @return the trace, or empty
   */
  @Override
  public Optional<Trace> getTrace(String traceId) {
    return await(getTraceAsync(traceId), "getTrace");
  }

  @Override
  public CompletableFuture<Optional<Trace>> getTraceAsync(String traceId) {
    return SignozApi.defer(
        () -> {
          String id = TraceIds.normalizeTraceId(traceId);
          return waterfall("getTrace", id)
              .thenCompose(
                  w -> {
                    if (w == null || !w.path("spans").isArray() || w.path("spans").isEmpty()) {
                      return CompletableFuture.completedFuture(Optional.<Trace>empty());
                    }
                    JsonNode spans = w.path("spans");
                    long total = Rows.num(w, "totalSpansCount").orElse(0);
                    if (w.path("hasMore").asBoolean(false) || total > spans.size()) {
                      long start = Rows.num(w, "startTimestampMillis").orElse(0);
                      long end = Rows.num(w, "endTimestampMillis").orElse(start) + 1;
                      return allSpans(id, new Window(start, end), new ArrayList<>())
                          .thenApply(all -> Optional.of(new Trace(id, Traces.sortSpans(all))));
                    }
                    List<Span> out = new ArrayList<>();
                    spans.forEach(s -> out.add(Rows.spanFromWaterfall(s)));
                    return CompletableFuture.completedFuture(
                        Optional.of(new Trace(id, Traces.sortSpans(out))));
                  });
        });
  }

  private CompletableFuture<List<Span>> allSpans(String id, Window w, List<Span> acc) {
    TraceQuery q =
        TraceQuery.builder()
            .filter("trace_id = " + FilterBuilder.quoteFilterString(id))
            .start(Instant.ofEpochMilli(w.startMs()))
            .end(Instant.ofEpochMilli(w.endMs()))
            .limit(MAX_QUERY_LIMIT)
            .order(SortOrder.ASC)
            .offset(acc.size())
            .build();
    return queryTracesAsync(q)
        .thenCompose(
            page -> {
              acc.addAll(page.spans());
              if (page.spans().size() < MAX_QUERY_LIMIT) {
                return CompletableFuture.completedFuture(acc);
              }
              return allSpans(id, w, acc);
            });
  }

  /**
   * Finds traces (requestType {@code trace}), newest first, at most {@code query.limit()}: a trace
   * matches when any of its spans matches service, operation, tags and the duration bounds. Start
   * time, span count (all spans of the trace), root service and root name come from each hit's
   * waterfall (millisecond precision); the duration is the root span's.
   *
   * @param query filter
   * @return summaries
   */
  @Override
  public List<TraceSummary> searchTraces(TraceSearchQuery query) {
    return await(searchTracesAsync(query), "searchTraces");
  }

  @Override
  public CompletableFuture<List<TraceSummary>> searchTracesAsync(TraceSearchQuery query) {
    return SignozApi.defer(
        () -> {
          String op = "searchTraces";
          TraceSearchQuery q = query == null ? TraceSearchQuery.builder().build() : query;
          int limit = checkLimit(op, q.limit(), 20);
          FilterBuilder f = FilterBuilder.create();
          if (q.service().isPresent() && !q.service().get().isEmpty()) {
            f = f.eq("service.name", q.service().get());
          }
          if (q.operation().isPresent() && !q.operation().get().isEmpty()) {
            f = f.eq("name", q.operation().get());
          }
          for (Map.Entry<String, String> t : new TreeMap<>(q.tags()).entrySet()) {
            f = f.eq(t.getKey(), t.getValue());
          }
          if (q.minDurationMicros().isPresent() && q.minDurationMicros().getAsLong() > 0) {
            f = f.gte("duration_nano", q.minDurationMicros().getAsLong() * 1000);
          }
          if (q.maxDurationMicros().isPresent() && q.maxDurationMicros().getAsLong() > 0) {
            f = f.lte("duration_nano", q.maxDurationMicros().getAsLong() * 1000);
          }
          String expr = f.build();
          Window w = window(q.start().orElse(null), q.end().orElse(null));
          Map<String, Object> spec = spec(Signal.TRACES, expr);
          spec.put("order", List.of(order("timestamp", SortOrder.DESC)));
          spec.put("limit", limit);
          return queryRange(op, "trace", w, spec)
              .thenCompose(
                  range -> {
                    List<JsonNode> hits = new ArrayList<>();
                    for (JsonNode row : rows(range.result())) {
                      if (TraceIds.isValidTraceId(Rows.str(row.path("data"), "trace_id"))) {
                        hits.add(row.path("data"));
                      }
                    }
                    return fill(op, hits, 0, new ArrayList<>());
                  });
        });
  }

  /** Completes the summaries from each hit's waterfall, {@link #SEARCH_CONCURRENCY} at a time. */
  private CompletableFuture<List<TraceSummary>> fill(
      String op, List<JsonNode> hits, int from, List<TraceSummary> acc) {
    if (from >= hits.size()) {
      return CompletableFuture.completedFuture(acc);
    }
    int to = Math.min(hits.size(), from + SEARCH_CONCURRENCY);
    List<CompletableFuture<TraceSummary>> batch = new ArrayList<>();
    for (JsonNode d : hits.subList(from, to)) {
      String id = TraceIds.normalizeTraceId(Rows.str(d, "trace_id"));
      batch.add(waterfall(op, id).thenApply(w -> summary(id, d, w)));
    }
    return CompletableFuture.allOf(batch.toArray(new CompletableFuture<?>[0]))
        .thenCompose(
            v -> {
              batch.forEach(b -> acc.add(b.join()));
              return fill(op, hits, to, acc);
            });
  }

  static TraceSummary summary(String id, JsonNode d, JsonNode w) {
    String service = Rows.str(d, "service.name");
    String name = Rows.str(d, "name");
    long duration = Rows.num(d, "duration_nano").orElse(0) / 1000;
    long count = Rows.num(d, "span_count").orElse(0);
    long start = 0;
    if (w != null) {
      start = Rows.num(w, "startTimestampMillis").orElse(0) * 1000;
      long total = Rows.num(w, "totalSpansCount").orElse(0);
      if (total > 0) {
        count = total;
      }
      String rs = Rows.str(w, "rootServiceName");
      String rn = Rows.str(w, "rootServiceEntryPoint");
      service = rs.isEmpty() ? service : rs;
      name = rn.isEmpty() ? name : rn;
    }
    return new TraceSummary(
        id,
        service.isEmpty() ? null : service,
        name.isEmpty() ? null : name,
        start,
        duration,
        (int) count);
  }

  /** Sorted group values of {@code count()} grouped by {@code field}. */
  private CompletableFuture<List<String>> groupNames(String field, String filter, TimeRange r) {
    TimeRange range = r == null ? TimeRange.defaults() : r;
    AggregateQuery q =
        aggregate(Signal.TRACES)
            .filter(filter)
            .groupBy(field)
            .count("count")
            .between(range.start(), range.end())
            .limit(MAX_QUERY_LIMIT)
            .build();
    return aggregateAsync(q)
        .thenApply(
            res -> {
              Set<String> out = new TreeSet<>();
              for (AggregateRow row : res.rows()) {
                Object v = row.groups().get(field);
                if (v != null && !String.valueOf(v).isEmpty()) {
                  out.add(String.valueOf(v));
                }
              }
              return List.copyOf(out);
            });
  }

  /**
   * Every service with spans in the last hour, sorted.
   *
   * @return service names
   */
  @Override
  public List<String> listServices() {
    return listServices(TimeRange.defaults());
  }

  @Override
  public CompletableFuture<List<String>> listServicesAsync() {
    return listServicesAsync(TimeRange.defaults());
  }

  /**
   * Every service with spans in a window ({@code count()} grouped by {@code service.name}), sorted.
   *
   * @param range window
   * @return service names
   */
  public List<String> listServices(TimeRange range) {
    return await(listServicesAsync(range), "listServices");
  }

  /**
   * Asynchronous {@link #listServices(TimeRange)}.
   *
   * @param range window
   * @return future names
   */
  public CompletableFuture<List<String>> listServicesAsync(TimeRange range) {
    return groupNames("service.name", "", range);
  }

  /**
   * The span names of a service in the last hour, sorted.
   *
   * @param service service name
   * @return operation names
   */
  @Override
  public List<String> listOperations(String service) {
    return listOperations(service, TimeRange.defaults());
  }

  @Override
  public CompletableFuture<List<String>> listOperationsAsync(String service) {
    return listOperationsAsync(service, TimeRange.defaults());
  }

  /**
   * The span names of a service in a window, sorted.
   *
   * @param service service name
   * @param range window
   * @return operation names
   */
  public List<String> listOperations(String service, TimeRange range) {
    return await(listOperationsAsync(service, range), "listOperations");
  }

  /**
   * Asynchronous {@link #listOperations(String, TimeRange)}.
   *
   * @param service service name
   * @param range window
   * @return future names
   */
  public CompletableFuture<List<String>> listOperationsAsync(String service, TimeRange range) {
    return SignozApi.defer(
        () -> {
          if (service == null || service.isBlank()) {
            throw new ValidationException("listOperations: service is required");
          }
          return groupNames(
              "name", FilterBuilder.create().eq("service.name", service).toString(), range);
        });
  }

  /**
   * The service dependency graph in a window ({@code POST /api/v1/dependency_graph}; default the
   * last hour), sorted by parent then child. Edges come from client-to-server spans and from client
   * spans with {@code db.system} or {@code messaging.system}.
   *
   * @param start window start or {@code null}
   * @param end window end or {@code null}
   * @return links
   */
  @Override
  public List<DependencyLink> getDependencies(Instant start, Instant end) {
    return await(getDependenciesAsync(start, end), "getDependencies");
  }

  @Override
  public CompletableFuture<List<DependencyLink>> getDependenciesAsync(Instant start, Instant end) {
    Window w = window(start, end);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("start", Long.toString(w.startMs() * 1_000_000L));
    body.put("end", Long.toString(w.endMs() * 1_000_000L));
    body.put("tags", List.of());
    return api.call(
            "getDependencies", TransportRequest.post("/api/v1/dependency_graph").json(body), false)
        .thenApply(
            edges -> {
              List<DependencyLink> out = new ArrayList<>();
              for (JsonNode e : edges) {
                out.add(
                    new DependencyLink(
                        Rows.str(e, "parent"),
                        Rows.str(e, "child"),
                        Rows.num(e, "callCount").orElse(0)));
              }
              out.sort(
                  Comparator.comparing(DependencyLink::parent)
                      .thenComparing(DependencyLink::child));
              return out;
            });
  }

  // ---------------------------------------------------------------- aggregations

  /**
   * Starts an aggregate builder bound to this client ({@link AggregateBuilder#run()}).
   *
   * @param signal logs or traces
   * @return a new builder
   */
  public AggregateBuilder aggregate(Signal signal) {
    return new AggregateBuilder(signal, this);
  }

  /**
   * Runs an aggregate query ({@code POST /api/v5/query_range}, requestType {@code scalar} or {@code
   * time_series}). SigNoz does not echo aliases (columns are named {@code __result_<i>}); values
   * are mapped back to the aliases by index.
   *
   * @param query the query
   * @return rows (scalar) or series (time series)
   * @throws ValidationException for a missing signal or aggregation, a bad field, limit, step or
   *     order
   */
  public AggregateResult aggregate(AggregateQuery query) {
    return await(aggregateAsync(query), "aggregate");
  }

  /**
   * Asynchronous {@link #aggregate(AggregateQuery)}.
   *
   * @param query the query
   * @return a future result
   */
  public CompletableFuture<AggregateResult> aggregateAsync(AggregateQuery query) {
    return SignozApi.defer(() -> aggregateNow(query));
  }

  /** Group-by reference; {@code service.name} is a resource field, {@code name} a span field. */
  static Map<String, Object> groupField(String name) {
    Map<String, Object> m = field(name);
    if (name.equals("service.name")) {
      m.put("fieldContext", "resource");
      m.put("fieldDataType", "string");
    } else if (name.equals("name")) {
      m.put("fieldContext", "span");
      m.put("fieldDataType", "string");
    }
    return m;
  }

  private CompletableFuture<AggregateResult> aggregateNow(AggregateQuery q) {
    String op = "aggregate";
    if (q.signal() == null) {
      throw new ValidationException(op + ": signal must be logs or traces");
    }
    if (q.aggregations().isEmpty()) {
      throw new ValidationException(op + ": at least one aggregation is required");
    }
    if (q.stepInterval() != null && q.stepInterval().isNegative()) {
      throw new ValidationException(op + ": stepInterval must not be negative");
    }
    int limit = checkLimit(op, q.limit(), DEFAULT_LIMIT);
    Map<String, Object> spec = spec(q.signal(), q.filter());
    List<String> aliases = new ArrayList<>();
    List<Object> aggs = new ArrayList<>();
    for (Aggregation a : q.aggregations()) {
      if (a.expression() == null || a.expression().isBlank()) {
        throw new ValidationException(op + ": aggregation expression is empty");
      }
      aliases.add(a.name());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("expression", a.expression());
      if (a.alias() != null && !a.alias().isEmpty()) {
        m.put("alias", a.alias());
      }
      aggs.add(m);
    }
    spec.put("aggregations", aggs);
    if (!q.groupBy().isEmpty()) {
      List<Object> groups = new ArrayList<>();
      for (String g : q.groupBy()) {
        groups.add(groupField(FilterBuilder.checkField(g)));
      }
      spec.put("groupBy", groups);
    }
    if (q.orderKey() != null && !q.orderKey().isBlank()) {
      spec.put(
          "order",
          List.of(
              order(
                  q.orderKey().trim(),
                  q.orderDirection() == null ? SortOrder.DESC : q.orderDirection())));
    }
    spec.put("limit", limit);
    String requestType = "scalar";
    if (q.isTimeSeries()) {
      requestType = "time_series";
      spec.put("stepInterval", Math.max(q.stepInterval().getSeconds(), 1));
    }
    Window w = window(q.start(), q.end());
    boolean scalar = requestType.equals("scalar");
    return queryRange(op, requestType, w, spec)
        .thenApply(
            range -> {
              if (range.result() == null) {
                return new AggregateResult(List.of(), List.of(), range.warnings());
              }
              return scalar
                  ? new AggregateResult(
                      parseScalar(range.result(), aliases), List.of(), range.warnings())
                  : new AggregateResult(
                      List.of(), parseTimeSeries(range.result(), aliases), range.warnings());
            });
  }

  private static String alias(List<String> aliases, int index) {
    return index >= 0 && index < aliases.size() ? aliases.get(index) : "__result_" + index;
  }

  static List<AggregateRow> parseScalar(JsonNode result, List<String> aliases) {
    List<AggregateRow> out = new ArrayList<>();
    JsonNode columns = result.path("columns");
    for (JsonNode row : result.path("data")) {
      Map<String, Object> groups = new LinkedHashMap<>();
      Map<String, Double> values = new LinkedHashMap<>();
      for (int i = 0; i < columns.size() && i < row.size(); i++) {
        JsonNode col = columns.get(i);
        JsonNode cell = row.get(i);
        if (Rows.str(col, "columnType").equals("aggregation")) {
          String name = alias(aliases, col.path("aggregationIndex").asInt(0));
          if (cell.isNumber()) {
            values.put(name, cell.asDouble());
          }
        } else {
          groups.put(Rows.str(col, "name"), Rows.plain(cell));
        }
      }
      out.add(new AggregateRow(groups, values));
    }
    return out;
  }

  static List<AggregateSeries> parseTimeSeries(JsonNode result, List<String> aliases) {
    List<AggregateSeries> out = new ArrayList<>();
    for (JsonNode agg : result.path("aggregations")) {
      String alias = alias(aliases, agg.path("index").asInt(0));
      for (JsonNode s : agg.path("series")) {
        Map<String, Object> labels = new LinkedHashMap<>();
        for (JsonNode l : s.path("labels")) {
          labels.put(Rows.str(l.path("key"), "name"), Rows.plain(l.get("value")));
        }
        List<AggregatePoint> points = new ArrayList<>();
        for (JsonNode v : s.path("values")) {
          points.add(
              new AggregatePoint(
                  Instant.ofEpochMilli(Rows.num(v, "timestamp").orElse(0)),
                  v.path("value").asDouble(0)));
        }
        out.add(new AggregateSeries(alias, labels, points));
      }
    }
    return out;
  }

  @Override
  public String toString() {
    return "SignozQueryClient{" + api.config().url() + "}";
  }
}

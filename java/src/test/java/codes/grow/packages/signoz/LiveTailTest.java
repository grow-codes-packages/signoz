package codes.grow.packages.signoz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.signoz.core.AuthenticationException;
import codes.grow.packages.signoz.core.CancelledException;
import codes.grow.packages.signoz.core.Hooks;
import codes.grow.packages.signoz.core.MockServer;
import codes.grow.packages.signoz.core.UnavailableException;
import codes.grow.packages.signoz.core.UnsupportedException;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.TailHandle;
import codes.grow.packages.signoz.logging.TailOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveTailTest {
  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private static void sse(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, b.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(b);
    }
  }

  private static void await(List<?> list, int n) throws InterruptedException {
    for (int i = 0; i < 100 && list.size() < n; i++) {
      Thread.sleep(50);
    }
  }

  @Test
  void streamsRecordsReconnectsAndDeduplicates() throws Exception {
    String event = Fixtures.read("tail.sse.txt");
    String second =
        ": comment\nevent: error\ndata: boom "
            + Fixtures.KEY
            + "\n\n"
            + "data: not json\n\n"
            + "data: {\"nodata\":1}\n\n"
            + "data: {\"data\":{\"id\":\"n2\",\"body\":\"multi\",\n"
            + "data: \"timestamp\":1795033285000000000}}\n\n"
            + "data: {\"data\":{\"body\":\"no id\",\"timestamp\":1795033286000000000}}";
    server
        .enqueue(ex -> sse(ex, event))
        .enqueue(ex -> sse(ex, event + second))
        .fallback(ex -> sse(ex, ""));
    List<Hooks.RequestInfo> requests = new CopyOnWriteArrayList<>();
    List<Integer> statuses = new CopyOnWriteArrayList<>();
    Hooks hooks =
        new Hooks() {
          @Override
          public void onRequest(RequestInfo info) {
            requests.add(info);
          }

          @Override
          public void onResponse(ResponseInfo info) {
            statuses.add(info.status());
          }
        };
    List<LogRecord> got = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    try (SignozClient client =
        SignozClient.create(
            Fixtures.config(server).hooks(hooks).header("X-T", "1").build(),
            Clock.fixed(Fixtures.NOW, ZoneOffset.UTC))) {
      TailHandle h =
          client
              .logs()
              .tail(
                  "service.name = 'tail-svc-fe67ae4b'",
                  got::add,
                  new TailOptions(
                      Optional.of(Instant.ofEpochMilli(1795033000000L)),
                      Duration.ofMillis(20),
                      errors::add));
      await(got, 3);
      h.stop();
      h.stop();
      assertThat(got)
          .extracting(LogRecord::body)
          .containsExactly("tail-fe67ae4b match", "multi", "no id");
      assertThat(got.get(0).resource()).containsEntry("service.name", "tail-svc-fe67ae4b");
      assertThat(errors).anyMatch(e -> e instanceof UnavailableException);
      assertThat(errors).allSatisfy(e -> assertThat(e.getMessage()).doesNotContain(Fixtures.KEY));
      String first = server.requests().get(0).uri();
      assertThat(first)
          .startsWith("/api/v3/logs/livetail?")
          .contains("start=1795033000000")
          .contains("filter=service.name%20%3D%20%27tail-svc-fe67ae4b%27");
      assertThat(server.requests().get(1).uri()).contains("start=1795033284000");
      assertThat(server.requests().get(0).header("SIGNOZ-API-KEY")).isEqualTo(Fixtures.KEY);
      assertThat(server.requests().get(0).header("Accept")).isEqualTo("text/event-stream");
      assertThat(server.requests().get(0).header("X-T")).isEqualTo("1");
      assertThat(requests.get(0).headers().toString()).doesNotContain(Fixtures.KEY);
      assertThat(statuses).contains(200);
    }
  }

  @Test
  void firstConnectionErrorsAreThrown() throws Exception {
    server
        .enqueue(401, Fixtures.read("ping.unauthenticated.json"))
        .enqueue(200, "<html></html>", "Content-Type", "text/html");
    try (SignozClient client = Fixtures.client(server)) {
      assertThatThrownBy(() -> client.logs().tail("", r -> {}, TailOptions.defaults()))
          .isInstanceOf(AuthenticationException.class)
          .message()
          .doesNotContain(Fixtures.KEY);
      assertThatThrownBy(() -> client.logs().tail("", r -> {}, null))
          .isInstanceOf(UnsupportedException.class);
      assertThat(server.requests().get(1).uri()).doesNotContain("filter=");
      assertThat(server.requests().get(1).uri()).contains("start=" + Fixtures.NOW.toEpochMilli());
      assertThatThrownBy(() -> client.logs().tail("", null, null))
          .isInstanceOf(ValidationException.class);
    }
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    try (SignozClient dead =
        SignozClient.builder().url("http://127.0.0.1:" + port).proxy(null).build()) {
      assertThatThrownBy(() -> dead.logs().tail("", r -> {}, null))
          .isInstanceOf(UnavailableException.class);
    }
  }

  @Test
  void nonRetryableReconnectErrorEndsTheTailAndCloseStopsIt() throws Exception {
    server
        .enqueue(ex -> sse(ex, Fixtures.read("tail.sse.txt")))
        .enqueue(403, Fixtures.read("error.forbidden.json"));
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    SignozClient client = Fixtures.client(server);
    client.logs().tail("", r -> {}, new TailOptions(null, Duration.ofMillis(10), errors::add));
    await(errors, 1);
    assertThat(errors).isNotEmpty();
    // a second, still running tail is stopped by close
    server.fallback(ex -> sse(ex, ""));
    TailHandle h =
        client.logs().tail("", r -> {}, new TailOptions(null, Duration.ofSeconds(5), null));
    client.close();
    assertThat(((LiveTail) h).isStopped()).isTrue();
    assertThatThrownBy(() -> client.logs().tail("", r -> {}, null))
        .isInstanceOf(CancelledException.class);
  }

  @Test
  void readAndDispatchEdgeCases() throws Exception {
    try (SignozClient client = Fixtures.client(server)) {
      List<LogRecord> got = new CopyOnWriteArrayList<>();
      LiveTail t =
          new LiveTail(
              new SignozApi(client.config(), client.transport()),
              null,
              got::add,
              TailOptions.defaults(),
              0,
              x -> {});
      t.dispatch("", "");
      t.read(
          new ByteArrayInputStream(
              "data: {\"data\":{\"id\":\"a\",\"body\":\"x\"},\"timestamp\":\"2026-11-18T20:21:24Z\"}\n\ndata: {\"data\":{\"id\":\"a\",\"body\":\"dup\"}}\n\n"
                  .getBytes(StandardCharsets.UTF_8)));
      assertThat(got).extracting(LogRecord::body).containsExactly("x");
      assertThat(t.lastMs()).isEqualTo(Instant.parse("2026-11-18T20:21:24Z").toEpochMilli());
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      LiveTail broken =
          new LiveTail(
              new SignozApi(client.config(), client.transport()),
              "",
              r -> {
                throw new IllegalStateException("callback errors are ignored");
              },
              TailOptions.defaults().withOnError(errors::add),
              0,
              x -> {});
      broken.read(
          new InputStream() {
            @Override
            public int read() throws IOException {
              throw new IOException("reset");
            }
          });
      assertThat(errors).singleElement().isInstanceOf(UnavailableException.class);
      broken.dispatch("", "{\"data\":{\"id\":\"z\",\"body\":\"y\"}}");
      broken.dispatch(
          "",
          "{\"data\":{\"id\":\"q\",\"trace_flags\":999,\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\"}}");
      broken.stop();
      broken.read(new ByteArrayInputStream("data: x\n\n".getBytes(StandardCharsets.UTF_8)));
      assertThatThrownBy(broken::connect).isInstanceOf(CancelledException.class);
    }
  }

  @Test
  void clientOptions() {
    var cfg =
        SignozConfig.builder()
            .proxy("https://proxy.invalid")
            .tls(
                codes.grow.packages.signoz.core.TlsOptions.builder()
                    .insecureSkipVerify(true)
                    .build())
            .build();
    assertThat(LiveTail.client(cfg.toTransportConfig())).isNotNull();
    var cfg2 = SignozConfig.builder().proxy("http://proxy.invalid:8080").build();
    assertThat(LiveTail.client(cfg2.toTransportConfig())).isNotNull();
    var cfg3 = SignozConfig.builder().proxy("http://proxy.invalid").build();
    assertThat(LiveTail.client(cfg3.toTransportConfig())).isNotNull();
  }
}

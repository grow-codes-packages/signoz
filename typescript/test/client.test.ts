import { inspect } from "node:util";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  PACKAGE_KEY,
  REDACTED,
  RetryPolicy,
  Secret,
  SignozClient,
  SignozConfig,
  SignozError,
} from "../src/index.js";
import { type MockServer, closedPort, startServer } from "./helpers/server.js";
import { API_KEY, startSignoz } from "./signoz-mock.js";

const servers: MockServer[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

async function signoz(...routes: Parameters<typeof startSignoz>): Promise<MockServer> {
  const s = await startSignoz(...routes);
  servers.push(s);
  return s;
}

const fast = { retry: { maxRetries: 1, initialBackoffMs: 1 } };

describe("SignozConfig", () => {
  it("uses defaults and freezes", () => {
    const c = new SignozConfig();
    expect(c.url).toBe("http://127.0.0.1:8080");
    expect(c.requestTimeoutMs).toBe(30_000);
    expect(c.retry).toBeInstanceOf(RetryPolicy);
    expect(c.apiKey).toBeUndefined();
    expect(c.ingestionKey).toBeUndefined();
    expect(c.otlpProtocol).toBe("http/protobuf");
    expect(c.otlpEndpoint).toBe("http://127.0.0.1:4318");
    expect(new SignozConfig({ otlpProtocol: "grpc" }).otlpEndpoint).toBe("http://127.0.0.1:4317");
    expect(Object.isFrozen(c)).toBe(true);
    expect(SignozConfig.from(c)).toBe(c);
    expect(SignozConfig.from().url).toBe(c.url);
    expect(SignozConfig.ENV_PREFIX).toBe(`GROW_${PACKAGE_KEY.toUpperCase()}_`);
  });

  it("keeps optional settings", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const c = new SignozConfig({
      proxy: "http://p:1",
      userAgentSuffix: "s",
      hooks,
      logger,
      apiKey: new Secret("abcd"),
      ingestionKey: "ingest-1",
      otlpEndpoint: "https://ingest.eu.signoz.cloud:443/",
      otlpProtocol: "grpc",
    });
    expect(c.proxy).toBe("http://p:1");
    expect(c.userAgentSuffix).toBe("s");
    expect(c.hooks).toBe(hooks);
    expect(c.logger).toBe(logger);
    expect(c.otlpEndpoint).toBe("https://ingest.eu.signoz.cloud:443");
    expect(c.knownSecrets()).toEqual(["abcd", "ingest-1"]);
  });

  it("validate() raises ConfigError", () => {
    expect(() => new SignozConfig({ url: "ftp://x" })).toThrow(ConfigError);
    expect(() => new SignozConfig({ apiKey: "   " })).toThrow(ConfigError);
    expect(() => new SignozConfig({ ingestionKey: " " })).toThrow(ConfigError);
    expect(() => new SignozConfig({ otlpEndpoint: "grpc://x:4317" })).toThrow(ConfigError);
    expect(() => new SignozConfig({ otlpEndpoint: "not a url" })).toThrow(ConfigError);
    expect(() => new SignozConfig({ otlpProtocol: "native" as never })).toThrow(ConfigError);
  });

  it("core.redaction.config: toString/JSON/inspect hide credentials", () => {
    const c = new SignozConfig({
      apiKey: "super-secret-key",
      ingestionKey: "ingest-secret-key",
      headers: { "x-api-key": "hdr-secret" },
    });
    for (const form of [String(c), JSON.stringify(c), inspect(c), `${c.apiKey}`]) {
      expect(form).not.toContain("super-secret-key");
      expect(form).not.toContain("ingest-secret-key");
      expect(form).not.toContain("hdr-secret");
    }
    expect(String(c)).toContain(REDACTED);
    expect(String(c)).toMatch(/SignozConfig\(/);
    expect(new SignozConfig().toJSON().apiKey).toBeNull();
    expect(new SignozConfig().toJSON().ingestionKey).toBeNull();
  });

  it("core.config.env-precedence: explicit > GROW_* > vendor > default", () => {
    const env = {
      GROW_SIGNOZ_URL: "http://grow:1",
      SIGNOZ_URL: "http://vendor:2",
      SIGNOZ_API_KEY: "vendor-key",
      SIGNOZ_INGESTION_KEY: "vendor-ingest",
      SIGNOZ_OTLP_ENDPOINT: "http://collector:4318",
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel:4318",
      OTEL_EXPORTER_OTLP_PROTOCOL: "grpc",
      GROW_SIGNOZ_REQUEST_TIMEOUT: "5s",
      GROW_SIGNOZ_MAX_RETRIES: "7",
    };
    const fromEnv = SignozConfig.fromEnv({}, env);
    expect(fromEnv.url).toBe("http://grow:1");
    expect(fromEnv.apiKey?.reveal()).toBe("vendor-key");
    expect(fromEnv.ingestionKey?.reveal()).toBe("vendor-ingest");
    expect(fromEnv.otlpEndpoint).toBe("http://collector:4318");
    expect(fromEnv.otlpProtocol).toBe("grpc");
    expect(fromEnv.requestTimeoutMs).toBe(5000);
    expect(fromEnv.retry.maxRetries).toBe(7);

    const grow = SignozConfig.fromEnv(
      {},
      {
        ...env,
        GROW_SIGNOZ_API_KEY: "grow-key",
        GROW_SIGNOZ_INGESTION_KEY: "grow-ingest",
        GROW_SIGNOZ_OTLP_ENDPOINT: "http://grow-collector:4317",
        GROW_SIGNOZ_OTLP_PROTOCOL: "http/protobuf",
      },
    );
    expect(grow.apiKey?.reveal()).toBe("grow-key");
    expect(grow.ingestionKey?.reveal()).toBe("grow-ingest");
    expect(grow.otlpEndpoint).toBe("http://grow-collector:4317");
    expect(grow.otlpProtocol).toBe("http/protobuf");

    expect(SignozConfig.fromEnv({}, { SIGNOZ_URL: "http://vendor:2" }).url).toBe("http://vendor:2");
    expect(
      SignozConfig.fromEnv({}, { OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel:4318" }).otlpEndpoint,
    ).toBe("http://otel:4318");
    const explicit = SignozConfig.fromEnv(
      { url: "http://explicit:3", apiKey: "explicit-key" },
      env,
    );
    expect(explicit.url).toBe("http://explicit:3");
    expect(explicit.apiKey?.reveal()).toBe("explicit-key");
    expect(SignozConfig.fromEnv({}, {}).url).toBe(SignozConfig.DEFAULT_URL);

    let err: unknown;
    try {
      SignozConfig.fromEnv({}, { GROW_SIGNOZ_OTLP_PROTOCOL: "carrier-pigeon-secret" });
    } catch (e) {
      err = e;
    }
    expect(err).toBeInstanceOf(ConfigError);
    expect((err as Error).message).toContain("GROW_SIGNOZ_OTLP_PROTOCOL");
    expect((err as Error).message).not.toContain("carrier-pigeon-secret");
  });
});

describe("SignozClient", () => {
  it("health, ping and serverVersion against the recorded API", async () => {
    const s = await signoz((req) =>
      req.url === "/api/v1/version" ? { body: { version: "v0.151.0", ee: "Y" } } : undefined,
    );
    const client = new SignozClient({ url: s.url, apiKey: API_KEY, ...fast });
    expect(await client.health()).toEqual({ healthy: true, status: "ok", details: {} });
    expect(s.requests[0]?.url).toBe("/api/v1/health?live=1");
    expect(s.requests[0]?.headers["signoz-api-key"]).toBeUndefined();
    expect(await client.ping()).toBeGreaterThanOrEqual(0);
    expect(s.requests[1]?.url).toBe("/api/v1/fields/keys?signal=logs&limit=1");
    expect(s.requests[1]?.headers["signoz-api-key"]).toBe(API_KEY);
    expect(await client.serverVersion()).toBe("0.151.0");
    expect(client.transport.userAgent).toContain(`grow-codes-${PACKAGE_KEY}/`);
    expect(client.traces).toBe(client.query);
    await client.close();
  });

  it("serverVersion raises UnsupportedError for the empty community version", async () => {
    const s = await signoz();
    const client = new SignozClient({ url: s.url, ...fast });
    await expect(client.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    const gone = await signoz((req) =>
      req.url === "/api/v1/version" ? { status: 404, body: {} } : undefined,
    );
    const client2 = new SignozClient({ url: gone.url, ...fast });
    await expect(client2.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    await client.close();
    await client2.close();
  });

  it("ping with a bad key raises AuthenticationError without leaking it", async () => {
    const s = await signoz();
    const client = new SignozClient(
      new SignozConfig({ url: s.url, apiKey: "wrong-key-xyz", ...fast }),
    );
    const err = await client.ping().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(SignozError);
    expect((err as SignozError).code).toBe("AUTHENTICATION");
    expect((err as SignozError).httpStatus).toBe(401);
    expect((err as Error).message).not.toContain("wrong-key-xyz");
    await client.close();
  });

  it("query calls without an API key raise ConfigError", async () => {
    const s = await signoz();
    const client = new SignozClient({ url: s.url, ...fast });
    await expect(client.ping()).rejects.toBeInstanceOf(ConfigError);
    expect(s.requests).toHaveLength(0);
    await client.close();
  });

  it("an HTML answer (unknown route on this server version) is UnsupportedError", async () => {
    const s = await signoz((req) =>
      req.url.startsWith("/api/v1/fields/keys")
        ? { headers: { "content-type": "text/html; charset=utf-8" }, body: "<!doctype html>" }
        : undefined,
    );
    const client = new SignozClient({ url: s.url, apiKey: API_KEY, ...fast });
    await expect(client.ping()).rejects.toMatchObject({ code: "UNSUPPORTED", httpStatus: 200 });
    expect(s.requests).toHaveLength(1);
    await client.close();
  });

  it("invalid JSON and unexpected envelopes are UnavailableError (not retried)", async () => {
    const s = await signoz(
      (req) =>
        req.url.startsWith("/api/v1/fields/keys?signal=logs&limit=1")
          ? { headers: { "content-type": "application/json" }, body: "{oops" }
          : undefined,
      (req) =>
        req.url.startsWith("/api/v1/fields/keys")
          ? { headers: { "content-type": "application/json" }, body: { status: "error" } }
          : undefined,
    );
    const client = new SignozClient({ url: s.url, apiKey: API_KEY, ...fast });
    await expect(client.ping()).rejects.toMatchObject({ code: "UNAVAILABLE", retryable: false });
    await expect(client.query.listLogFields()).rejects.toMatchObject({ code: "UNAVAILABLE" });
    await client.close();
  });

  it("health reports unhealthy statuses and empty bodies", async () => {
    const empty = await signoz((req) =>
      req.url.startsWith("/api/v1/health")
        ? { headers: { "content-type": "application/json" }, body: "" }
        : undefined,
    );
    const ok = new SignozClient({ url: empty.url, ...fast });
    expect(await ok.health()).toMatchObject({ healthy: true, status: "ok" });
    await ok.close();
    const detail = await signoz((req) =>
      req.url.startsWith("/api/v1/health")
        ? { body: { status: "degraded", checks: { ch: false } } }
        : undefined,
    );
    const degraded = new SignozClient({ url: detail.url, ...fast });
    expect(await degraded.health()).toEqual({
      healthy: false,
      status: "degraded",
      details: { checks: '{"ch":false}' },
    });
    await degraded.close();
    const down = await startServer([{ status: 503, body: { status: "error" } }]);
    servers.push(down);
    const sick = new SignozClient({ url: down.url, retry: { maxRetries: 0 } });
    expect(await sick.health()).toMatchObject({ healthy: false, status: "unhealthy" });
    await sick.close();
  });

  it("core.health.unreachable: closed port -> healthy=false, status=unreachable", async () => {
    const port = await closedPort();
    const client = new SignozClient({ url: `http://127.0.0.1:${port}`, ...fast });
    expect(await client.health()).toEqual({
      healthy: false,
      status: "unreachable",
      details: { error: "UNAVAILABLE" },
    });
    await client.close();
  });

  it("health rethrows cancellation", async () => {
    const s = await signoz();
    const client = new SignozClient({ url: s.url });
    await expect(client.health({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(client.ping({ signal: AbortSignal.abort() })).rejects.toBeDefined();
    await client.close();
  });

  it("core.close.idempotent: close twice is safe", async () => {
    const s = await signoz();
    const client = new SignozClient({ url: s.url, apiKey: API_KEY });
    await client.health();
    await client.close();
    await client.close();
    // every call on a closed client raises CANCELLED (same in all languages)
    await expect(client.ping()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.health()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.serverVersion()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.query.queryLogs()).rejects.toMatchObject({ code: "CANCELLED" });
    expect(() => client.logClient()).toThrow(/client is closed/);
    expect(() => client.initTracing({ serviceName: "svc" })).toThrow(/client is closed/);
    expect(() => client.logs.tail("", () => undefined)).toThrow(/client is closed/);
  });

  it("fromEnv builds from GROW_* variables", async () => {
    const s = await signoz();
    process.env.GROW_SIGNOZ_URL = s.url;
    try {
      const client = SignozClient.fromEnv({ apiKey: API_KEY });
      expect(client.config.url).toBe(s.url);
      expect(await client.ping()).toBeGreaterThanOrEqual(0);
      await client.close();
    } finally {
      delete process.env.GROW_SIGNOZ_URL;
    }
  });
});

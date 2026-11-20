// Service-owned: generated once by sdk-kit, then maintained in this repository.

import { describe, expect, it } from "vitest";
import { ConfigError } from "../src/core/index.js";
import { connectLogClient } from "../src/logging-connect.js";

describe("connectLogClient", () => {
  it("builds a log client without I/O or returns undefined", async () => {
    let connection: ReturnType<typeof connectLogClient>;
    try {
      connection = connectLogClient({
        resource: { "service.name": "t" },
        env: {},
        onDropped: () => undefined,
      });
    } catch (err) {
      // the service needs more settings (credentials): initLogging falls back to stdout
      expect(err).toBeInstanceOf(ConfigError);
      return;
    }
    if (connection !== undefined) {
      expect(typeof connection.logs.send).toBe("function");
      await connection.close();
    }
  });
});

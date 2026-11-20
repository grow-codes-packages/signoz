// Service-owned: generated once by sdk-kit, then maintained in this repository.
// Connector used by `initLogging()` (logging/init.ts): builds the SDK log transport from the
// package's own `GROW_SIGNOZ_*` (and vendor) environment variables.

import { SignozClient } from "./client.js";
import type { BatchingLogClientOptions, LogClient } from "./logging/client.js";
import type { LogConnection, LogConnector } from "./logging/init.js";

/** A client that builds batching log clients as `logClient(options)` (the sdk-kit convention). */
interface WithLogClient {
  logClient?: (options: BatchingLogClientOptions) => LogClient;
}

/**
 * Builds the SDK transport for `initLogging()`:
 * `SignozClient.fromEnv().logClient({ resource, batch: { onDrop: onDropped } })`.
 * Returns `undefined` (stdout only) when the client has no `logClient()`. Services with a different
 * constructor replace this function. Must not do network I/O.
 */
export const connectLogClient: LogConnector = ({
  resource,
  onDropped,
}): LogConnection | undefined => {
  const client = SignozClient.fromEnv();
  const factory = (client as unknown as WithLogClient).logClient;
  if (typeof factory !== "function") {
    void client.close();
    return undefined;
  }
  const logs = factory.call(client, { resource, batch: { onDrop: onDropped } });
  return {
    logs,
    close: async () => {
      await logs.close();
      await client.close();
    },
  };
};

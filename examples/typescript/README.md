# TypeScript example

Sends logs and traces to the SigNoz collector over OTLP, then reads them back with the query API
(logs, traces, services, operations, latency aggregation).

```sh
(cd ../../typescript && npm install && npm run build)
npm install
GROW_SIGNOZ_URL=http://127.0.0.1:8080 \
GROW_SIGNOZ_API_KEY=... \
GROW_SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318 \
npm start
```

`GROW_SIGNOZ_API_KEY` is a SigNoz service-account key (Viewer role is enough). Replace the `file:`
dependency in `package.json` with a version (`npm install @grow-codes/signoz`) to run it against the
published package.

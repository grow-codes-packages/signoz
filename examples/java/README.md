# Java examples

Install the package into your local Maven repository, point it at a running SigNoz and run an
example:

```bash
make -C ../../java pack            # builds and installs codes.grow.packages:signoz
export GROW_SIGNOZ_URL=http://127.0.0.1:8080
export GROW_SIGNOZ_API_KEY=...          # service-account key (any role, viewer is enough)
export GROW_SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318   # collector (QueryExample sends logs and spans)
mvn -q compile exec:java                                                  # BasicExample
mvn -q compile exec:java -Dexec.mainClass=codes.grow.packages.signoz.examples.AsyncExample
mvn -q compile exec:java -Dexec.mainClass=codes.grow.packages.signoz.examples.QueryExample
```

Against the published package, set `-Dsdk.version=<version>` (or edit `pom.xml`) instead of
installing it locally.

| Class | Shows |
| - | - |
| `BasicExample` | blocking client: health, ping, server version, error handling |
| `AsyncExample` | `…Async` methods, combining futures, cancellation |
| `HooksAndLoggingExample` | request hooks, a logger, retries and timeouts |
| `QueryExample` | send logs and a trace over OTLP, then read them back: `queryLogs` with `FilterBuilder`, keyset paging, `getTrace`, `searchTraces`, services, an aggregate |

# StreamFence - Embeddable Java Socket.IO Server Library

<p align="center">
  <img src="assets/logo.png" alt="StreamFence logo" width="320">
</p>

<p align="center">
  <a href="https://github.com/MoshPe/StreamFence/actions/workflows/ci.yml"><img src="https://github.com/MoshPe/StreamFence/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="https://github.com/MoshPe/StreamFence/actions/workflows/codeql.yml"><img src="https://github.com/MoshPe/StreamFence/actions/workflows/codeql.yml/badge.svg?branch=main" alt="CodeQL"></a>
  <a href="https://codecov.io/gh/MoshPe/StreamFence"><img src="https://codecov.io/gh/MoshPe/StreamFence/branch/main/graph/badge.svg" alt="codecov"></a>
  <a href="https://central.sonatype.com/artifact/io.github.moshpe/streamfence-core"><img src="https://img.shields.io/maven-central/v/io.github.moshpe/streamfence-core.svg" alt="Maven Central"></a>
  <a href="https://github.com/MoshPe/StreamFence/releases/latest"><img src="https://img.shields.io/github/v/release/MoshPe/StreamFence" alt="GitHub Release"></a>
  <a href="https://openjdk.org/projects/jdk/17/"><img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk" alt="Java 17"></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"></a>
</p>

Production-ready delivery control for Java Socket.IO servers - backpressure, retries, queue protection, spill-to-disk overflow, and configurable per-namespace delivery modes.

Reference Java implementation for the StreamFence family and the source of truth for the JS port's runtime behavior.

---

## Table of contents

- [What it is](#what-it-is)
- [When to use one server vs two](#when-to-use-one-server-vs-two)
- [Install](#install)
- [Quick start](#quick-start)
- [Client-side protocol](#client-side-protocol)
- [Config file loading](#config-file-loading)
- [Delivery modes](#delivery-modes)
- [Overflow policies](#overflow-policies)
- [Spill to disk](#spill-to-disk)
- [Authentication](#authentication)
- [TLS](#tls)
- [Metrics & management](#metrics--management)
- [Event listeners](#event-listeners)
- [Server API reference](#server-api-reference)
- [NamespaceSpec builder](#namespacespec-builder)
- [API reference — public types](#api-reference--public-types)
- [Examples](#examples)
- [Demo](#demo)
- [Status / roadmap](#status--roadmap)
- [License](#license)

---

## What it is

StreamFence wraps `netty-socketio` with a delivery-control layer that prevents clients from being overwhelmed, keeps critical messages retryable, and gives you visibility into what happens when queues fill up.

Each Socket.IO namespace gets its own delivery policy: choose between fire-and-forget `BEST_EFFORT` or acknowledged `AT_LEAST_ONCE` delivery, configure per-client queue limits, and pick an overflow strategy. The library handles per-client queuing, backpressure, retry scheduling, spill-to-disk recovery, and Prometheus metrics so your application code just calls `server.publish()`.

The project is split into two Maven modules:

- `streamfence-core` - the embeddable library
- `streamfence-demo` - runnable examples, dashboard launcher, and smoke coverage

---

## When to use one server vs two

For most production workloads, run two separate servers:

| Server | Port | Namespaces | Delivery |
|---|---|---|---|
| Feed server | 9092 | `/feed`, `/prices`, `/updates` | `BEST_EFFORT` - high-frequency, loss-tolerant |
| Control server | 9094 | `/commands`, `/alerts` | `AT_LEAST_ONCE` - low-frequency, reliable |

Why separate ports? `AT_LEAST_ONCE` adds acknowledgement tracking, retry scheduling, and stricter queue behavior. Mixing reliable and best-effort traffic on one server means the reliable path's pressure can affect feed latency. Separating them keeps each server tuned to its workload.

Both servers can still run in the same JVM process. See [MixedWorkloadExample](streamfence-demo/src/main/java/io/streamfence/demo/examples/MixedWorkloadExample.java) for the Java version of that layout.

---

## Install

Add `streamfence-core` to your Maven project:

```xml
<dependency>
    <groupId>io.github.moshpe</groupId>
    <artifactId>streamfence-core</artifactId>
    <version>1.0.7</version>
</dependency>
```

Requires Java 25.

---

## Quick start

```java
import io.streamfence.DeliveryMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServer;
import java.util.Map;

NamespaceSpec feedSpec = NamespaceSpec.builder("/feed")
        .topic("snapshot")
        .deliveryMode(DeliveryMode.BEST_EFFORT)
        .overflowAction(OverflowAction.DROP_OLDEST)
        .maxQueuedMessagesPerClient(128)
        .build();

try (SocketIoServer server = SocketIoServer.builder()
        .host("127.0.0.1")
        .port(9092)
        .namespace(feedSpec)
        .buildServer()) {

    server.start();

    // Publish to all subscribers of /feed > snapshot
    server.publish("/feed", "snapshot", Map.of("price", 42.5, "ts", System.currentTimeMillis()));

    // Publish to a specific client only
    server.publishTo("/feed", "client-session-id", "snapshot",
            Map.of("price", 42.5, "ts", System.currentTimeMillis()));
}
```

---

## Client-side protocol

StreamFence uses a simple event-based protocol over Socket.IO. In Java, outbound topic deliveries arrive on the `topic-message` event with a metadata envelope.

### Subscribing

```javascript
socket.emit("subscribe", { topic: "snapshot" });
socket.on("subscribed", msg => console.log("subscribed", msg));
```

### Receiving messages

```javascript
socket.on("topic-message", envelope => {
  const { metadata, payload } = envelope;
  console.log(metadata.topic, payload);
});
```

### Acknowledging messages (`AT_LEAST_ONCE` only)

```javascript
socket.on("topic-message", envelope => {
  const { metadata } = envelope;
  if (metadata?.ackRequired) {
    socket.emit("ack", { topic: metadata.topic, messageId: metadata.messageId });
  }
});
```

If the server does not receive an `ack` within `ackTimeoutMs`, it retries up to `maxRetries`.

### Unsubscribing

```javascript
socket.emit("unsubscribe", { topic: "snapshot" });
socket.on("unsubscribed", msg => console.log("unsubscribed", msg));
```

---

## Config file loading

Instead of building servers entirely in code, you can load a `SocketIoServerSpec` from YAML or JSON and keep customizing the builder afterward.

```java
import io.streamfence.SocketIoServer;
import io.streamfence.SocketIoServerSpec;
import java.nio.file.Path;

SocketIoServerSpec spec = SocketIoServerSpec.fromYaml(Path.of("config/application.yaml"));

try (SocketIoServer server = SocketIoServer.builder()
        .fromYaml(Path.of("config/application.yaml"))
        .port(9192)
        .buildServer()) {
    server.start();
}
```

Classpath loading is also supported:

```java
SocketIoServerSpec spec = SocketIoServerSpec.fromClasspath("application.yaml");
```

### Config file schema

```yaml
host: 0.0.0.0
port: 9092
transportMode: WS
managementHost: 0.0.0.0
managementPort: 9093
shutdownDrainMs: 10000
senderThreads: 0
pingIntervalMs: 20000
pingTimeoutMs: 40000
maxFramePayloadLength: 5242880
maxHttpContentLength: 5242880
compressionEnabled: true
authMode: NONE
spillRootPath: .streamfence-spill
staticTokens:
  demo-client: secret-token
namespaces:
  /feed:
    authRequired: false
topicPolicies:
  - namespace: /feed
    topics: [snapshot, delta]
    deliveryMode: BEST_EFFORT
    overflowAction: DROP_OLDEST
    maxQueuedMessagesPerClient: 128
    maxQueuedBytesPerClient: 1048576
    ackTimeoutMs: 1000
    maxRetries: 0
    coalesce: false
    allowPolling: true
    authRequired: false
    maxInFlight: 1
```

When `transportMode: WSS`, add a `tls` block with certificate and key paths.

---

## Delivery modes

| Mode | Guarantee | Acks | Use case |
|---|---|---|---|
| `BEST_EFFORT` | At most once | None | Live feeds, price tickers, presence updates |
| `AT_LEAST_ONCE` | At least once | Required | Commands, alerts, critical state changes |

### `AT_LEAST_ONCE` constraints

`AT_LEAST_ONCE` namespaces enforce the following at build time:

| Constraint | Reason |
|---|---|
| `overflowAction` must be `REJECT_NEW` | Other overflow actions would silently discard reliable messages |
| `coalesce` must be `false` | Coalescing would replace messages that need individual acknowledgement |
| `maxRetries` must be `>= 1` | At-least-once semantics require an actual retry budget |
| `maxInFlight` must not exceed `maxQueuedMessagesPerClient` | The in-flight window cannot be larger than the queue itself |

---

## Overflow policies

Applied when a client's per-topic lane is full and a new message arrives.

| Action | Behavior | Best for |
|---|---|---|
| `REJECT_NEW` | Incoming message rejected and overflow counted | `AT_LEAST_ONCE`; strict backpressure |
| `DROP_OLDEST` | Oldest queued message removed, new message accepted | Live feeds where stale data is harmless |
| `COALESCE` | Matching queued entry replaced with the latest value | Ticker-style state feeds |
| `SNAPSHOT_ONLY` | Queue replaced with just the newest message | Single-value snapshot streams |
| `SPILL_TO_DISK` | Excess messages persisted to disk and replayed later | Burst absorption without unbounded heap growth |

---

## Spill to disk

When a namespace uses `OverflowAction.SPILL_TO_DISK`, messages that exceed the in-memory lane limit are written to disk and transparently replayed in FIFO order when the lane drains.

### How it works

1. Messages are queued in memory until `maxQueuedMessagesPerClient` is reached.
2. Once the lane is full, overflow entries are serialized and written atomically as `.tmp` then `.spill` files.
3. When the in-memory queue empties, spill files are drained back into memory in publish order.
4. On unsubscribe or disconnect, spill files for that lane are cleaned up.

### Configuration

```java
SocketIoServer server = SocketIoServer.builder()
        .spillRootPath("/var/lib/streamfence-spill")
        .namespace(NamespaceSpec.builder("/feed")
                .topic("snapshot")
                .deliveryMode(DeliveryMode.BEST_EFFORT)
                .overflowAction(OverflowAction.SPILL_TO_DISK)
                .maxQueuedMessagesPerClient(16)
                .maxQueuedBytesPerClient(524_288)
                .build())
        .buildServer();
```

Spill files are organized as:

```text
{spillRootPath}/{namespace}/{topic}/00000001.spill
```

Each spill increments `wsserver_messages_spilled_total` with `namespace` and `topic` labels.

---

## Authentication

Set `authMode: TOKEN` in config or call `.authMode(AuthMode.TOKEN)` in code, then provide either static tokens or a custom `TokenValidator`.

```java
import io.streamfence.AuthDecision;
import io.streamfence.AuthMode;
import io.streamfence.SocketIoServer;
import java.util.concurrent.CompletableFuture;

SocketIoServer.builder()
        .authMode(AuthMode.TOKEN)
        .staticToken("demo-client", "secret-token")
        .buildServer();

SocketIoServer.builder()
        .authMode(AuthMode.TOKEN)
        .tokenValidator((token, namespace) ->
                CompletableFuture.completedFuture(
                        "secret-token".equals(token)
                                ? AuthDecision.accept("user-alice")
                                : AuthDecision.reject("invalid token")))
        .buildServer();
```

When auth is enabled, clients can supply the token in the Socket.IO handshake or in the subscribe payload.

---

## TLS

```yaml
transportMode: WSS
tls:
  certChainPemPath: /etc/ssl/cert.pem
  privateKeyPemPath: /etc/ssl/key.pem
  privateKeyPassword:
  keyStorePassword: changeit
  protocol: TLSv1.3
```

Java StreamFence reloads PEM material periodically so certificate rotation does not require a restart.

---

## Metrics & management

Use the built-in Micrometer-backed metrics collector and enable the management HTTP server for scraping.

```java
String prometheusText = server.metrics().scrape();
// GET http://127.0.0.1:9093/metrics when managementPort is enabled
```

### Available metrics

| Metric | Labels | Description |
|---|---|---|
| `wsserver_connections_opened_total` | `namespace` | Total successful client connections |
| `wsserver_connections_closed_total` | `namespace` | Total client disconnections |
| `wsserver_messages_published_total` | `namespace`, `topic` | Total outbound messages published |
| `wsserver_bytes_published_total` | `namespace`, `topic` | Total outbound published bytes |
| `wsserver_messages_received_total` | `namespace`, `topic` | Total inbound client messages |
| `wsserver_bytes_received_total` | `namespace`, `topic` | Total inbound bytes |
| `wsserver_queue_overflow_total` | `namespace`, `topic`, `reason` | Queue overflow events |
| `wsserver_retry_count_total` | `namespace`, `topic` | Retry attempts |
| `wsserver_retry_exhausted_total` | `namespace`, `topic` | Exhausted retry outcomes |
| `wsserver_messages_dropped_total` | `namespace`, `topic` | Messages dropped by `DROP_OLDEST` |
| `wsserver_messages_coalesced_total` | `namespace`, `topic` | Messages replaced by coalescing |
| `wsserver_messages_spilled_total` | `namespace`, `topic` | Messages spilled to disk |
| `wsserver_auth_rejected_total` | `namespace` | Auth rejections |
| `wsserver_auth_rate_limited_total` | `namespace` | Auth rate-limited rejections |

The management endpoint also exposes standard JVM and process metrics through Micrometer.

---

## Event listeners

Register listeners with the builder. Implement only the callbacks you care about; listener failures are caught and isolated from the runtime.

```java
SocketIoServer.builder()
        .listener(new ServerEventListener() {
            @Override
            public void onServerStarted(ServerStartedEvent event) {
                System.out.println("started on " + event.host() + ":" + event.port());
            }

            @Override
            public void onQueueOverflow(QueueOverflowEvent event) {
                System.err.println("overflow on " + event.namespace() + " > " + event.topic());
            }
        })
        .buildServer();
```

The listener surface covers server lifecycle, connection events, subscribe/unsubscribe events, publish acceptance or rejection, auth failures, retries, and retry exhaustion.

---

## Server API reference

### `SocketIoServerBuilder`

| Method | Description |
|---|---|
| `host(String)` | Bind address, default `"0.0.0.0"` |
| `port(int)` | Socket.IO port |
| `managementPort(int)` | Prometheus management port; `0` disables it |
| `transportMode(TransportMode)` | `WS` or `WSS` |
| `authMode(AuthMode)` | `NONE` or `TOKEN` |
| `staticToken(String, String)` / `staticTokens(Map)` | Register static auth tokens |
| `tokenValidator(TokenValidator)` | Custom async token validation |
| `spillRootPath(String)` | Root directory for spill files |
| `namespace(NamespaceSpec)` | Add a namespace |
| `listener(ServerEventListener)` | Add an event listener |
| `fromYaml(Path)` / `fromJson(Path)` / `fromClasspath(String)` | Seed config from a file or resource |
| `build()` | Return the immutable `SocketIoServerSpec` |
| `buildServer()` | Return a ready-to-start `SocketIoServer` |

### `SocketIoServer`

| Method | Description |
|---|---|
| `start()` | Start the Socket.IO and management servers |
| `close()` | Graceful shutdown via `AutoCloseable` |
| `publish(namespace, topic, payload)` | Broadcast to all subscribers of a topic |
| `publishTo(namespace, clientId, topic, payload)` | Send to one specific client |
| `metrics()` | Access the metrics collector |
| `spec()` | Access the immutable server spec snapshot |

---

## NamespaceSpec builder

Create namespace policies with `NamespaceSpec.builder("/path")`:

```java
NamespaceSpec spec = NamespaceSpec.builder("/prices")
        .topics(java.util.List.of("bid", "ask", "last"))
        .deliveryMode(DeliveryMode.BEST_EFFORT)
        .overflowAction(OverflowAction.COALESCE)
        .maxQueuedMessagesPerClient(128)
        .maxQueuedBytesPerClient(1_048_576)
        .coalesce(true)
        .build();
```

| Method | Default | Description |
|---|---|---|
| `topic(String)` / `topics(List<String>)` | none | Register one or more topics |
| `deliveryMode(DeliveryMode)` | `BEST_EFFORT` | Delivery guarantee |
| `overflowAction(OverflowAction)` | `REJECT_NEW` | Overflow strategy |
| `maxQueuedMessagesPerClient(int)` | `64` | Per-client queue depth limit |
| `maxQueuedBytesPerClient(long)` | `524288` | Per-client queued byte limit |
| `ackTimeoutMs(long)` | `1000` | Ack timeout for reliable delivery |
| `maxRetries(int)` | `0` | Retry budget |
| `coalesce(boolean)` | `false` | Enable coalescing |
| `allowPolling(boolean)` | `true` | Allow HTTP long-polling |
| `maxInFlight(int)` | `1` | In-flight reliable window |
| `authRequired(boolean)` | `false` | Require token auth for the namespace |
| `build()` | none | Validate and return the immutable spec |

---

## API reference — public types

Public API lives in `io.streamfence`:

| Type | Description |
|---|---|
| `SocketIoServer` | Main server entry point; `AutoCloseable` |
| `SocketIoServerBuilder` | Fluent server builder |
| `SocketIoServerSpec` | Immutable server configuration snapshot |
| `NamespaceSpec` | Immutable namespace policy |
| `NamespaceSpec.Builder` | Namespace builder |
| `DeliveryMode` | `BEST_EFFORT`, `AT_LEAST_ONCE` |
| `OverflowAction` | `REJECT_NEW`, `DROP_OLDEST`, `COALESCE`, `SNAPSHOT_ONLY`, `SPILL_TO_DISK` |
| `TransportMode` | `WS`, `WSS` |
| `AuthMode` | `NONE`, `TOKEN` |
| `AuthDecision` | Auth accept/reject result |
| `TokenValidator` | Async token validation contract |
| `TLSConfig` | WSS certificate and protocol settings |
| `ServerMetrics` | Micrometer-backed metrics surface |
| `ServerEventListener` | Optional runtime callback interface |

---

## Examples

See the runnable Java examples in `streamfence-demo`:

- [SingleServerExample](streamfence-demo/src/main/java/io/streamfence/demo/examples/SingleServerExample.java) - one `BEST_EFFORT` namespace, three publishes, clean stop
- [MultiNamespaceExample](streamfence-demo/src/main/java/io/streamfence/demo/examples/MultiNamespaceExample.java) - one server with multiple namespaces and different overflow policies
- [MixedWorkloadExample](streamfence-demo/src/main/java/io/streamfence/demo/examples/MixedWorkloadExample.java) - two servers side by side: a high-frequency feed server and a reliable control server

All three are exercised by [ExamplesSmokeTest](streamfence-demo/src/test/java/io/streamfence/demo/examples/ExamplesSmokeTest.java) on every build.

Reference YAML configurations for the mixed-workload example:

- [mixed-workload-feed.yaml](streamfence-demo/src/main/resources/examples/mixed-workload-feed.yaml)
- [mixed-workload-control.yaml](streamfence-demo/src/main/resources/examples/mixed-workload-control.yaml)

---

## Demo

The `streamfence-demo` module ships a multi-process launcher with a browser dashboard. Start everything with one command:

```bash
mvn -pl streamfence-demo exec:java
```

Default endpoints:

- Socket.IO server: `http://127.0.0.1:9092`
- Prometheus metrics: `http://127.0.0.1:9093/metrics`
- Browser dashboard: `http://127.0.0.1:9094`

Override ports at launch time:

```bash
mvn -pl streamfence-demo exec:java -Dexec.args="--server-port=9192 --management-port=9193 --console-port=9194"
```

If you are working on the repository itself, run the full verification path with:

```bash
mvn --no-transfer-progress clean verify -Dgpg.skip=true
```

---

## Status / roadmap

v1 is complete and published. Planned for v2:

- Persistent `AT_LEAST_ONCE` queues that survive restart
- Multi-node or cluster-aware spill coordination
- Formal benchmark coverage

---

## License

[Apache-2.0](LICENSE)

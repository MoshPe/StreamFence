# wsserver — Embeddable Java Socket.IO Server Library

## Overview

`wsserver` is an embeddable Java Socket.IO server library built on `netty-socketio`. It is delivered as a library artifact (`io.streamfence:wsserver`) with a runnable multi-process demo module (`io.streamfence:wsserver-demo`) that starts a launcher, a server child JVM, separate client JVMs, and a browser diagnostics console against a bundled `application.yaml`.

The project is a two-module Maven build:

- **`wsserver`** — the library. Publishes main, sources, and javadoc jars. Public API lives in the flat `io.streamfence` package. Internals live under `io.streamfence.internal.*` (config, security, transport, delivery, protocol, observability) and are annotated `@Internal`.
- **`wsserver-demo`** — a runnable multi-process sample. The launcher starts the demo server, automated client personas, and an embedded browser console that streams runtime events over SSE.

Public API surface (all in `io.streamfence`):

- `SocketIoServer`, `SocketIoServerBuilder`, `SocketIoServerSpec`
- `NamespaceSpec`
- `AuthMode`, `AuthDecision`, `TokenValidator`
- `DeliveryMode`, `OverflowAction`, `TransportMode`, `TLSConfig`
- `ServerEventListener` and its event records
- `ServerMetrics`

Anything in `io.streamfence.internal.*` is subject to change without notice.

## Quick Start — Code-First

```java
import io.streamfence.AuthMode;
import io.streamfence.DeliveryMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServer;

import java.util.List;
import java.util.Map;

try(SocketIoServer server = SocketIoServer.builder()
        .host("127.0.0.1")
        .port(9092)
        .authMode(AuthMode.NONE)
        .namespace(NamespaceSpec.builder("/feed")
                .authRequired(false)
                .deliveryMode(DeliveryMode.BEST_EFFORT)
                .overflowAction(OverflowAction.REJECT_NEW)
                .maxQueuedMessagesPerClient(64)
                .maxQueuedBytesPerClient(524_288)
                .ackTimeoutMs(1_000)
                .maxRetries(0)
                .coalesce(false)
                .allowPolling(true)
                .maxInFlight(1)
                .topics(List.of("prices", "quotes"))
                .build())
        .buildServer()){
        server.

start();

// Broadcast to every subscriber of a topic:
    server.

publish("/feed","prices",Map.of("value", 42));

        // Deliver only to a specific client session:
        server.

publishTo("/feed","abc-123","prices",Map.of("value", 99));
        }
```

## Quick Start — YAML / JSON Configuration

The library can also seed itself from a YAML or JSON document. Both are fully equivalent to the code-first builder and every loaded field can still be overridden fluently.

```java
import io.streamfence.SocketIoServer;
import io.streamfence.SocketIoServerSpec;

import java.nio.file.Path;

// Load directly into a spec:
SocketIoServerSpec spec = SocketIoServerSpec.fromYaml(Path.of("config/application.yaml"));

// Or seed the builder (listeners and TokenValidator are retained):
try(
        SocketIoServer server = SocketIoServer.builder()
                .fromYaml(Path.of("config/application.yaml"))
                .listener(new MyListener())
                .port(9092) // fluent overrides win
                .buildServer()){
        server.

        start();
}

        // Or load from the classpath (.yaml or .json):
        SocketIoServerSpec fromCp = SocketIoServerSpec.fromClasspath("application.yaml");
```

Parse failures are wrapped in `IllegalArgumentException` with the source path and, when available, the offending line number.

## Observability Hooks

Register listeners during build time:

```java
SocketIoServer server = SocketIoServer.builder()
        .listener(new ServerEventListener() {
            @Override public void onServerStarted(ServerStartedEvent event) { }
            @Override public void onPublishAccepted(PublishAcceptedEvent event) { }
        })
        // ...
        .buildServer();
```

Available callbacks cover server lifecycle, client connect/disconnect, subscribe/unsubscribe, publish accepted/rejected, queue overflow, auth rejected, retry/retry-exhausted, and bulk-plane usage. Listener failures are isolated from the runtime.

## Runtime Behavior

Each namespace has one shared policy and a list of topic names. Different behavior should be modeled as a different namespace.

Namespace policies support:

- delivery mode: `BEST_EFFORT` or `AT_LEAST_ONCE`
- overflow action: `DROP_OLDEST`, `REJECT_NEW`, `COALESCE`, `SNAPSHOT_ONLY`, `SPILL_TO_DISK`
- per-client message and byte bounds
- ack timeout and retry count
- polling allowance
- namespace auth requirement
- reliable pipelining via `maxInFlight`

Current runtime guarantees:

- `BEST_EFFORT` topics may drop or coalesce according to policy
- `AT_LEAST_ONCE` topics assign `messageId`, require `ack`, and retry until budget is exhausted
- exact-once delivery is not provided
- queues are bounded per client/topic lane
- session state, retries, and queues are in-memory and single-node

## Supported Events

Inbound: `subscribe`, `unsubscribe`, `publish`, `ack`.

Outbound: `topic-message`, `subscribed`, `unsubscribed`, `error`.

`topic-message` contract: every outbound envelope is `{ metadata, payload }`.

## Metrics

Micrometer `SimpleMeterRegistry` is wired in-process through `SocketIoServer.metrics()`. The runtime records connection open/close, publish counts and bytes, queue overflow, retries and retry exhaustion, dropped and coalesced messages, and bulk-plane usage. If `managementPort` is configured, the runtime also exposes a Prometheus scrape endpoint over HTTP.

## Building

Requirements: Java 25, Maven 3.9+.

```bash
mvn clean install
```

This produces:

- `wsserver/target/wsserver-<version>.jar`
- `wsserver/target/wsserver-<version>-sources.jar`
- `wsserver/target/wsserver-<version>-javadoc.jar`
- `wsserver-demo/target/wsserver-demo-<version>.jar`

## Running The Demo

The demo launcher is now a showcase dashboard for the library rather than a thin diagnostics console. It starts:

- one launcher JVM
- one demo server JVM
- one preset-driven client swarm child JVM
- one browser dashboard served over HTTP

The dashboard exposes five real server presets backed by bundled YAML configs:

- `throughput` - `/non-reliable`, coalescing on, `24` automated connections at `100 ms`
- `realtime` - `/non-reliable`, websocket-only freshness-first updates, `32` automated connections at `50 ms`
- `reliable` - `/reliable`, token auth, `AT_LEAST_ONCE`, `16` automated connections at `150 ms`
- `bulk` - `/bulk`, polling off, `4` automated connections, `512 KiB` sample payload every `500 ms`
- `pressure` - `/reliable`, tight queues plus delayed acknowledgements so retries and overflow become visible quickly

Default endpoints:

- Socket.IO server: `http://127.0.0.1:9092`
- Metrics endpoint: `http://127.0.0.1:9093`
- Browser dashboard: `http://127.0.0.1:9094`

Start everything with one command:

```bash
mvn -pl wsserver-demo exec:java
```

You can override ports at launch time:

```bash
mvn -pl wsserver-demo exec:java -Dexec.args="--server-port=9192 --management-port=9193 --console-port=9194"
```

What the dashboard shows:

- active clients, total messages, live messages/sec, send bytes/sec, receive bytes/sec
- retry, queue overflow, dropped, and coalesced counters from the Prometheus management scrape
- per-namespace counters and the preset config story (`deliveryMode`, `overflowAction`, `coalesce`, `maxInFlight`, queue bounds, polling/auth settings)
- a live SSE timeline with merged launcher, server, and client events
- manual actions for preset switching, persona restart, scenario pause/resume, targeted samples, and browser-side publish testing

To enable TLS/WSS in the sample config, switch `transportMode` to `WSS` and fill in the PEM settings under `tls`.

## Module Layout

```
wsserver-parent/
├── pom.xml                 ← parent, dependencyManagement, pluginManagement
├── wsserver/               ← library artifact (io.streamfence:wsserver)
│   ├── src/main/java/io/wsserver/                 ← public API
│   └── src/main/java/io/wsserver/internal/...     ← internals (@Internal)
└── wsserver-demo/          ← multi-process demo (io.streamfence:wsserver-demo)
    ├── src/main/java/io/wsserver/demo/Main.java
    ├── src/main/java/io/wsserver/demo/launcher/...
    ├── src/main/java/io/wsserver/demo/server/...
    ├── src/main/java/io/wsserver/demo/client/...
    ├── src/main/java/io/wsserver/demo/console/...
    └── src/main/resources/application.yaml
```

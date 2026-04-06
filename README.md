# StreamFence - Embeddable Java Socket.IO Server Library

[![CI](https://github.com/MoshPe/StreamFence/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MoshPe/StreamFence/actions/workflows/ci.yml)
[![CodeQL](https://github.com/MoshPe/StreamFence/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/MoshPe/StreamFence/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/MoshPe/StreamFence/branch/main/graph/badge.svg)](https://codecov.io/gh/MoshPe/StreamFence)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.moshpe/streamfence-core.svg)](https://central.sonatype.com/artifact/io.github.moshpe/streamfence-core)
[![GitHub Release](https://img.shields.io/github/v/release/MoshPe/StreamFence)](https://github.com/MoshPe/StreamFence/releases/latest)
[![Java](https://img.shields.io/badge/Java-25-blue?logo=openjdk)](https://openjdk.org/projects/jdk/25/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## Why StreamFence

`StreamFence` is an embeddable Java Socket.IO server library built on `netty-socketio` for teams that need live topic delivery with bounded memory, explicit backpressure behavior, and optional reliable delivery.

It is designed for cases where "just push events" is not enough:

- some streams need raw throughput and freshness
- some streams need message acknowledgement and retries
- some clients are slow and must not be allowed to grow queues without bound
- operators need metrics and hooks that explain what the server is doing under pressure

The project is a two-module Maven build:

- `streamfence-core` - the library. Public API lives in the flat `io.streamfence` package. Internals live under `io.streamfence.internal.*` and are not stable API.
- `streamfence-demo` - a runnable multi-process showcase that starts a launcher, a demo server, automated client swarms, and a browser dashboard.

Public API surface:

- `SocketIoServer`, `SocketIoServerBuilder`, `SocketIoServerSpec`
- `NamespaceSpec`
- `AuthMode`, `AuthDecision`, `TokenValidator`
- `DeliveryMode`, `OverflowAction`, `TransportMode`, `TLSConfig`
- `ServerEventListener` and its event records
- `ServerMetrics`

Anything in `io.streamfence.internal.*` is subject to change without notice.

## Quick Start - Code-First

```java
import io.streamfence.AuthMode;
import io.streamfence.DeliveryMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServer;

import java.util.List;
import java.util.Map;

try (SocketIoServer server = SocketIoServer.builder()
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
        .buildServer()) {
    server.start();

    server.publish("/feed", "prices", Map.of("value", 42));
    server.publishTo("/feed", "abc-123", "prices", Map.of("value", 99));
}
```

## Quick Start - YAML / JSON Configuration

The library can also seed itself from YAML or JSON. Loaded values can still be overridden fluently before `buildServer()`.

```java
import io.streamfence.SocketIoServer;
import io.streamfence.SocketIoServerSpec;

import java.nio.file.Path;

SocketIoServerSpec spec = SocketIoServerSpec.fromYaml(Path.of("config/application.yaml"));

try (SocketIoServer server = SocketIoServer.builder()
        .fromYaml(Path.of("config/application.yaml"))
        .listener(new MyListener())
        .port(9092)
        .buildServer()) {
    server.start();
}

SocketIoServerSpec fromClasspath = SocketIoServerSpec.fromClasspath("application.yaml");
```

Parse failures are wrapped in `IllegalArgumentException` and include the source path and, when available, the offending line number.

## Delivery presets

The demo ships five reference presets. They are not special runtime-only modes; they are bundled examples of normal StreamFence configuration and client behavior.

- `throughput` - high-volume best-effort traffic on `/non-reliable`, tuned to keep updates flowing with low overhead.
- `realtime` - websocket-only live updates on `/non-reliable`, tuned for freshness over backlog.
- `reliable` - authenticated `AT_LEAST_ONCE` delivery on `/reliable`, with acknowledgements and pipelined in-flight reliable messages.
- `bulk` - large payload flow on `/bulk`, tuned to show transfer behavior and payload metadata under load.
- `pressure` - authenticated reliable traffic with tight queues and delayed acknowledgements, tuned to surface retries and overflow quickly.

Use these presets as examples of intent:

- throughput-first live feeds
- freshness-first realtime updates
- durable client delivery with ack/retry
- large payload transfer
- pressure testing and operational rehearsal

## Reliability model

StreamFence supports two delivery modes per namespace:

- `BEST_EFFORT`
- `AT_LEAST_ONCE`

`BEST_EFFORT` prioritizes low overhead and freshness. Messages may be dropped or coalesced according to overflow policy. There is no per-message acknowledgement tracking.

`AT_LEAST_ONCE` assigns a `messageId` to each outbound topic message, marks the message as ack-required, waits for the client to send `ack`, and retries until the retry budget is exhausted.

Protocol shape:

- inbound events: `subscribe`, `unsubscribe`, `publish`, `ack`
- outbound events: `topic-message`, `subscribed`, `unsubscribed`, `error`
- outbound `topic-message` payload: `{ metadata, payload }`

For reliable delivery:

- `metadata.messageId` identifies the delivery attempt chain
- `metadata.ackRequired` tells the client that it must acknowledge the message
- the client must emit `ack { topic, messageId }`
- if the ack is missing past `ackTimeoutMs`, the server retries up to `maxRetries`

What StreamFence does not provide:

- exact-once delivery
- durable persistent queues
- cross-node coordination for retries or queue state

Current reliable state is in-memory and single-node.

## Backpressure and overflow behavior

Every client/topic lane is bounded by:

- `maxQueuedMessagesPerClient`
- `maxQueuedBytesPerClient`

When a client cannot keep up, StreamFence applies the configured `OverflowAction`:

- `REJECT_NEW` - reject newly enqueued messages once the lane is full
- `DROP_OLDEST` - drop older queued messages to admit fresher ones
- `COALESCE` - collapse queued snapshots for the same topic into the newest state
- `SNAPSHOT_ONLY` - keep the latest snapshot-oriented state instead of retaining every intermediate update
- `SPILL_TO_DISK` - reserved policy surface for overflow strategies beyond in-memory queuing

Practical guidance:

- use `REJECT_NEW` when order and explicit rejection matter more than freshness
- use `DROP_OLDEST` when freshness matters more than historical completeness
- use `COALESCE` or snapshot-oriented policies for state feeds where only the newest value matters

For `AT_LEAST_ONCE` namespaces, the configuration is tighter by design:

- overflow must be `REJECT_NEW`
- coalescing is not allowed
- `maxRetries` must be positive
- `maxInFlight` cannot exceed the per-client queued message bound

## Client safety and writability

Clients are expected to behave safely against the server's delivery model.

Subscription and publish safety:

- subscribe only to known topics in the namespace
- supply a token when the namespace requires auth
- do not assume polling is always allowed; some namespaces require websocket-capable transport
- expect `error` events for unknown topics, rejected auth, or transport mismatch

Reliable client behavior:

- when `ackRequired=true`, the client must send `ack { topic, messageId }`
- delaying or omitting ack causes retries
- under sustained lag, reliable queues can fill and new messages can be rejected

Best-effort client behavior:

- no ack is required
- under pressure, the client may observe drops or coalesced state according to policy

"Writability" in StreamFence is practical rather than magical: a client is considered healthy only while its per-topic lane stays within queue bounds and the transport can keep draining messages. When a client is too slow, StreamFence protects the server by applying bounded queue policy instead of allowing unbounded growth.

## Configuration

Configuration is namespace-centric. Each namespace defines one shared policy and a list of topic names. If two sets of topics need different delivery or backpressure behavior, model them as separate namespaces.

Key namespace controls:

- `deliveryMode`
- `overflowAction`
- `maxQueuedMessagesPerClient`
- `maxQueuedBytesPerClient`
- `ackTimeoutMs`
- `maxRetries`
- `coalesce`
- `allowPolling`
- `authRequired`
- `maxInFlight`
- `topics`

Key server controls:

- host and ports
- transport mode and TLS
- ping intervals and frame limits
- auth mode and static tokens
- sender thread count
- management scrape endpoint
- auth reject window controls

The code-first builder and `SocketIoServerSpec` are equivalent ways to express the same runtime policy. The demo presets are simply packaged examples of those same knobs.

## Observability Hooks

Register listeners during build time:

```java
import io.streamfence.ServerEventListener;
import io.streamfence.SocketIoServer;

SocketIoServer server = SocketIoServer.builder()
        .listener(new ServerEventListener() {
            @Override public void onServerStarted(ServerStartedEvent event) { }
            @Override public void onPublishAccepted(PublishAcceptedEvent event) { }
            @Override public void onRetry(RetryEvent event) { }
        })
        .buildServer();
```

Available callbacks cover:

- server lifecycle
- client connect and disconnect
- subscribe and unsubscribe
- publish accepted and rejected
- queue overflow
- auth rejected
- retry and retry exhausted

Listener failures are isolated from the runtime so observability hooks do not bring down the server.

## Metrics

`SocketIoServer.metrics()` exposes the Micrometer registry used by the runtime. StreamFence records:

- connection open and close counts
- published message counts and bytes
- received message counts and bytes
- queue overflow counts
- retry and retry-exhausted counts
- dropped and coalesced message counts

If `managementPort` is configured, the runtime also exposes a Prometheus scrape endpoint over HTTP. In the demo, the dashboard uses that scrape as the source of truth for throughput, connections, queue pressure, and retry signals.

## Demo scenarios

The demo is organized around five scenarios, each meant to prove a different part of the library:

- `throughput` demonstrates high-volume best-effort delivery with `24` automated connections publishing every `100 ms`
- `realtime` demonstrates websocket-only freshness-first delivery with `32` automated connections publishing every `50 ms`
- `reliable` demonstrates authenticated `AT_LEAST_ONCE` delivery with `16` automated connections publishing every `150 ms`
- `bulk` demonstrates authenticated large-payload transfer with `4` automated connections sending a `512 KiB` sample every `500 ms`
- `pressure` demonstrates reliable retry and overflow behavior with `8` automated connections publishing every `100 ms` and delaying ack by `900 ms`

These scenarios are intended to show:

- message rate under best-effort load
- receive/send byte rates
- retry behavior when acknowledgements are delayed
- queue pressure signals such as overflow, dropped, and coalesced counters
- how different namespace policies change the operational profile

## Benchmarking

The current supported benchmarking workflow is demo-driven rather than a dedicated microbenchmark harness.

Use the demo presets plus the management metrics/dashboard to run repeatable comparisons:

- use `throughput` to measure raw best-effort message rate
- use `realtime` to compare freshness-oriented updates and websocket-only transport behavior
- use `reliable` to measure stable acknowledged delivery under normal conditions
- use `bulk` to compare large payload throughput and byte-rate behavior
- use `pressure` to observe retry, overflow, and lag behavior under stress

For each run, treat the Prometheus management scrape and dashboard counters as the source of truth:

- messages per second
- send bytes per second
- receive bytes per second
- active clients
- retries and retry exhaustion
- queue overflow, dropped, and coalesced counts

StreamFence does not currently ship a separate formal benchmark harness in this repo.

## When to use each preset

- Use `throughput` for high-volume feeds where occasional drop/coalesce behavior is acceptable and low overhead matters most.
- Use `realtime` for websocket-first live feeds where freshness matters more than retaining every intermediate update.
- Use `reliable` for business-critical client delivery where the consumer must acknowledge what it received.
- Use `bulk` for testing large payload transfer and verifying byte-rate, payload metadata, and transfer limits.
- Use `pressure` for rehearsal of failure behavior, alerting, retry tuning, and queue-bound stress validation.

## Running The Demo

The demo launcher starts:

- one launcher JVM
- one demo server JVM
- one preset-driven client swarm child JVM
- one browser dashboard served over HTTP

Default endpoints:

- Socket.IO server: `http://127.0.0.1:9092`
- Metrics endpoint: `http://127.0.0.1:9093`
- Browser dashboard: `http://127.0.0.1:9094`

Start everything with one command:

```bash
mvn -pl streamfence-demo exec:java
```

Override ports at launch time:

```bash
mvn -pl streamfence-demo exec:java -Dexec.args="--server-port=9192 --management-port=9193 --console-port=9194"
```

What the dashboard shows:

- active clients, total messages, live messages per second, send bytes per second, receive bytes per second
- retry, queue overflow, dropped, and coalesced counters from the Prometheus management scrape
- per-namespace counters and config breakdown
- a sampled live timeline of launcher, server, and client activity
- payload metadata and preset deep-dive explanations
- manual actions for preset switching, persona restart, scenario pause and resume, targeted samples, and browser-side publish testing

To enable TLS or WSS in the sample config, switch `transportMode` to `WSS` and fill in the PEM settings under `tls`.

## Building

Requirements:

- Java 25
- Maven 3.9+

Build the full project:

```bash
mvn clean install
```

This produces artifacts from:

- `streamfence-core/target`
- `streamfence-demo/target`

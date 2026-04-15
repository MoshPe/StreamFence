package io.streamfence.demo.examples;

import io.streamfence.DeliveryMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServer;
import java.net.ServerSocket;
import java.util.Map;

/**
 * Mixed-workload example that demonstrates running two servers side by side —
 * one for high-frequency market data, one for reliable command delivery.
 *
 * <p>Reference YAML configurations are provided in
 * {@code streamfence-demo/src/main/resources/examples/}:
 * <ul>
 *   <li>{@code mixed-workload-feed.yaml} — {@code /feed} namespace,
 *       BEST_EFFORT + DROP_OLDEST</li>
 *   <li>{@code mixed-workload-control.yaml} — {@code /control} namespace,
 *       AT_LEAST_ONCE + REJECT_NEW</li>
 * </ul>
 *
 * <p>The static {@link #buildFeedServer()} / {@link #buildControlServer()}
 * factories are used by the smoke test so servers can start on ephemeral
 * ports without blocking.
 */
public class MixedWorkloadExample {

    static int nextPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * Builds the feed server (BEST_EFFORT, high-frequency market data).
     * Mirrors the settings in {@code examples/mixed-workload-feed.yaml}.
     */
    public static SocketIoServer buildFeedServer() throws Exception {
        return SocketIoServer.builder()
                .host("127.0.0.1")
                .port(nextPort())
                .namespace(NamespaceSpec.builder("/feed")
                        .topics(java.util.List.of("prices", "quotes"))
                        .deliveryMode(DeliveryMode.BEST_EFFORT)
                        .overflowAction(OverflowAction.DROP_OLDEST)
                        .maxQueuedMessagesPerClient(64)
                        .maxQueuedBytesPerClient(524_288)
                        .ackTimeoutMs(1_000)
                        .maxRetries(0)
                        .build())
                .buildServer();
    }

    /**
     * Builds the control server (AT_LEAST_ONCE, reliable command delivery).
     * Mirrors the settings in {@code examples/mixed-workload-control.yaml}.
     */
    public static SocketIoServer buildControlServer() throws Exception {
        return SocketIoServer.builder()
                .host("127.0.0.1")
                .port(nextPort())
                .namespace(NamespaceSpec.builder("/control")
                        .topic("commands")
                        .deliveryMode(DeliveryMode.AT_LEAST_ONCE)
                        .overflowAction(OverflowAction.REJECT_NEW)
                        .maxQueuedMessagesPerClient(128)
                        .maxQueuedBytesPerClient(1_048_576)
                        .ackTimeoutMs(2_000)
                        .maxRetries(3)
                        .maxInFlight(4)
                        .build())
                .buildServer();
    }

    public static void main(String[] args) throws Exception {  // NOSONAR
        try (SocketIoServer feedServer    = buildFeedServer();
             SocketIoServer controlServer = buildControlServer()) {

            feedServer.start();
            controlServer.start();

            feedServer.publish("/feed",    "prices",   Map.of("bid", 100.0, "ask", 100.05));
            feedServer.publish("/feed",    "quotes",   Map.of("symbol", "AAPL", "price", 185.0));
            controlServer.publish("/control", "commands", Map.of("action", "REBALANCE"));

            System.out.println("MixedWorkloadExample: published — stopping both servers.");
        }
    }
}

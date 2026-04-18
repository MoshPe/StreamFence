package io.streamfence.demo.examples;

import io.streamfence.DeliveryMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServer;
import io.streamfence.SocketIoServerBuilder;
import java.net.ServerSocket;
import java.util.Map;

/**
 * Multi-namespace example demonstrating three independent delivery strategies.
 *
 * <ul>
 *   <li>{@code /prices} — {@link DeliveryMode#BEST_EFFORT} + {@link OverflowAction#DROP_OLDEST}
 *       (bid/ask feed)</li>
 *   <li>{@code /snapshots} — {@link DeliveryMode#BEST_EFFORT} +
 *       {@link OverflowAction#SNAPSHOT_ONLY} (portfolio snapshot)</li>
 *   <li>{@code /alerts} — {@link DeliveryMode#AT_LEAST_ONCE} +
 *       {@link OverflowAction#REJECT_NEW} (critical alerts)</li>
 * </ul>
 *
 * <p>The static {@link #buildServer()} factory is used by the smoke test so
 * the server can start on port 0 without blocking.
 */
public class MultiNamespaceExample {

    static int nextPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    public static SocketIoServer buildServer() throws Exception {
        SocketIoServerBuilder builder = SocketIoServer.builder()
                .host("127.0.0.1")
                .port(nextPort())
                // /prices: fast-moving market data — drop oldest on overflow
                .namespace(NamespaceSpec.builder("/prices")
                        .topic("bid-ask")
                        .deliveryMode(DeliveryMode.BEST_EFFORT)
                        .overflowAction(OverflowAction.DROP_OLDEST)
                        .maxQueuedMessagesPerClient(64)
                        .maxQueuedBytesPerClient(524_288)
                        .ackTimeoutMs(1_000)
                        .maxRetries(0)
                        .build())
                // /snapshots: replace whole state on each publish
                .namespace(NamespaceSpec.builder("/snapshots")
                        .topic("portfolio")
                        .deliveryMode(DeliveryMode.BEST_EFFORT)
                        .overflowAction(OverflowAction.SNAPSHOT_ONLY)
                        .maxQueuedMessagesPerClient(1)
                        .maxQueuedBytesPerClient(524_288)
                        .ackTimeoutMs(1_000)
                        .maxRetries(0)
                        .build())
                // /alerts: guaranteed delivery, reject new if full
                .namespace(NamespaceSpec.builder("/alerts")
                        .topic("critical")
                        .deliveryMode(DeliveryMode.AT_LEAST_ONCE)
                        .overflowAction(OverflowAction.REJECT_NEW)
                        .maxQueuedMessagesPerClient(256)
                        .maxQueuedBytesPerClient(1_048_576)
                        .ackTimeoutMs(2_000)
                        .maxRetries(3)
                        .maxInFlight(4)
                        .build());
        return builder.buildServer();
    }

    public static void main(String[] args) throws Exception {
        try (SocketIoServer server = buildServer()) {
            server.start();
            server.publish("/prices",    "bid-ask",   Map.of("bid", 100.0, "ask", 100.05));
            server.publish("/snapshots", "portfolio", Map.of("total", 50_000.0));
            server.publish("/alerts",    "critical",  Map.of("code", "MARGIN_CALL"));
            System.out.println("MultiNamespaceExample: published one message per namespace — stopping.");
        }
    }
}

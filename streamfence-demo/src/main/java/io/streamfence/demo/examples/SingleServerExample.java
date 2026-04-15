package io.streamfence.demo.examples;

import io.streamfence.DeliveryMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServer;
import io.streamfence.SocketIoServerBuilder;
import java.net.ServerSocket;
import java.util.Map;

/**
 * Minimal single-server example.
 *
 * <p>Starts a BEST_EFFORT server on an ephemeral port, publishes three
 * messages to the {@code /feed} namespace, and stops cleanly.
 *
 * <p>The static {@link #buildServer()} factory is intentionally separated
 * from {@link #main(String[])} so smoke tests can start the server on port 0
 * without blocking.
 */
public class SingleServerExample {

    /** Returns an available ephemeral port on the loopback interface. */
    static int nextPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    public static SocketIoServer buildServer() throws Exception {
        SocketIoServerBuilder builder = SocketIoServer.builder()
                .host("127.0.0.1")
                .port(nextPort())
                .namespace(NamespaceSpec.builder("/feed")
                        .topic("snapshot")
                        .deliveryMode(DeliveryMode.BEST_EFFORT)
                        .overflowAction(OverflowAction.DROP_OLDEST)
                        .maxQueuedMessagesPerClient(64)
                        .maxQueuedBytesPerClient(524_288)
                        .ackTimeoutMs(1_000)
                        .maxRetries(0)
                        .build());
        return builder.buildServer();
    }

    public static void main(String[] args) throws Exception {  // NOSONAR
        try (SocketIoServer server = buildServer()) {
            server.start();
            for (int i = 1; i <= 3; i++) {
                server.publish("/feed", "snapshot", Map.of("seq", i, "value", i * 10));
            }
            System.out.println("SingleServerExample: published 3 messages — stopping.");
        }
    }
}

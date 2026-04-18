package io.streamfence.demo.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.SocketIoServer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Smoke tests that exercise the runnable examples without actually calling
 * {@code main()} (which would block or require an explicit stop signal).
 *
 * <p>Each test builds the server(s) on port 0, starts, publishes at least one
 * message, and stops cleanly — all within 10 seconds.
 */
class ExamplesSmokeTest {

    @Test
    @Timeout(10)
    void singleServerExampleStartsPublishesAndStops() throws Exception {
        try (SocketIoServer server = SingleServerExample.buildServer()) {
            server.start();
            server.publish("/feed", "snapshot", Map.of("seq", 1, "value", 42));
            assertThat(server.spec().namespaces()).hasSize(1);
            assertThat(server.spec().namespaces().get(0).path()).isEqualTo("/feed");
        }
    }

    @Test
    @Timeout(10)
    void multiNamespaceExampleStartsPublishesAndStops() throws Exception {
        try (SocketIoServer server = MultiNamespaceExample.buildServer()) {
            server.start();
            server.publish("/prices",    "bid-ask",   Map.of("bid", 99.0, "ask", 99.5));
            server.publish("/snapshots", "portfolio", Map.of("total", 1_000.0));
            server.publish("/alerts",    "critical",  Map.of("code", "TEST"));
            assertThat(server.spec().namespaces()).hasSize(3);
        }
    }

    @Test
    @Timeout(10)
    void mixedWorkloadExampleStartsBothServersPublishesAndStops() throws Exception {
        try (SocketIoServer feedServer    = MixedWorkloadExample.buildFeedServer();
             SocketIoServer controlServer = MixedWorkloadExample.buildControlServer()) {

            feedServer.start();
            controlServer.start();

            feedServer.publish("/feed",    "prices",   Map.of("bid", 100.0, "ask", 100.5));
            feedServer.publish("/feed",    "quotes",   Map.of("symbol", "GOOG", "price", 180.0));
            controlServer.publish("/control", "commands", Map.of("action", "REBALANCE"));

            assertThat(feedServer.spec().namespaces())
                    .extracting(n -> n.path())
                    .containsExactly("/feed");
            assertThat(controlServer.spec().namespaces())
                    .extracting(n -> n.path())
                    .containsExactly("/control");
        }
    }
}

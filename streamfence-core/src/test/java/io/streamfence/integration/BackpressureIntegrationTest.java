package io.streamfence.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.streamfence.internal.transport.SocketServerBootstrap;
import io.streamfence.AuthMode;
import io.streamfence.DeliveryMode;
import io.streamfence.internal.config.NamespaceConfig;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.ServerConfig;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.TransportMode;
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification that the per-client overflow actions trigger and
 * that the Prometheus management endpoint exposes their counters.
 *
 * <p>Deterministic strategy: the test uses {@code AT_LEAST_ONCE +
 * maxQueuedMessagesPerClient=1} with a very large ack timeout. The client
 * never acknowledges, so the first published message stays pinned in the
 * queue as {@code awaitingAck}. Every subsequent publish therefore hits the
 * full-queue branch for its configured overflow action — one topic per
 * action, one assertion per counter.
 *
 * <p>{@code SNAPSHOT_ONLY} is not covered here because the lane-level
 * semantics are already covered by {@code ClientLaneTest} and routing it
 * through the dispatcher would add no new wiring coverage.
 */
@Tag("slow")
class BackpressureIntegrationTest {

    @Test
    void overflowActionsTriggerAndExposeMetricsEndToEnd() throws Exception {
        int port = nextPort();
        int managementPort = nextPort();

        TopicPolicy rejectTopic = new TopicPolicy(
                "/non-reliable", "overflow-reject",
                DeliveryMode.AT_LEAST_ONCE, OverflowAction.REJECT_NEW,
                1, 65536, 60_000, 3, false, true, false, 1);
        TopicPolicy dropTopic = new TopicPolicy(
                "/non-reliable", "overflow-drop",
                DeliveryMode.AT_LEAST_ONCE, OverflowAction.DROP_OLDEST,
                1, 65536, 60_000, 3, false, true, false, 1);
        // AT_LEAST_ONCE + coalesce is normally rejected by ServerConfigLoader,
        // but constructing ServerConfig directly bypasses the validator. We
        // use it here so the head stays pinned awaiting ack, giving every
        // subsequent publish a deterministic coalesce partner.
        TopicPolicy coalesceTopic = new TopicPolicy(
                "/non-reliable", "overflow-coalesce",
                DeliveryMode.AT_LEAST_ONCE, OverflowAction.COALESCE,
                1, 65536, 60_000, 3, true, true, false, 1);

        ServerConfig config = new ServerConfig(
                "127.0.0.1", port, TransportMode.WS, null,
                15000, 30000, 6291456, 6291456,
                false, false, null, AuthMode.NONE, Map.of(),
                Map.of(
                        "/non-reliable", new NamespaceConfig(false),
                        "/reliable", new NamespaceConfig(false),
                        "/bulk", new NamespaceConfig(false)
                ),
                List.of(rejectTopic, dropTopic, coalesceTopic),
                "127.0.0.1", managementPort, 10000, 0, 60000, 20);

        try (SocketServerBootstrap bootstrap = new SocketServerBootstrap(config)) {
            bootstrap.start();

            CountDownLatch subscribedLatch = new CountDownLatch(3);
            IO.Options options = IO.Options.builder()
                    .setForceNew(true)
                    .setReconnection(false)
                    .setTransports(new String[]{"websocket"})
                    .setTimeout(5000)
                    .build();
            Socket socket = IO.socket("http://127.0.0.1:" + port + "/non-reliable", options);
            try {
                socket.on(Socket.EVENT_CONNECT, args -> {
                    socket.emit("subscribe", subscribeFor("overflow-reject"));
                    socket.emit("subscribe", subscribeFor("overflow-drop"));
                    socket.emit("subscribe", subscribeFor("overflow-coalesce"));
                });
                socket.on("subscribed", args -> subscribedLatch.countDown());
                // Intentionally no "ack" emitted — the AT_LEAST_ONCE head
                // stays pinned for the duration of the test.
                socket.connect();
                assertThat(subscribedLatch.await(10, TimeUnit.SECONDS)).isTrue();

                // Publish directly through the dispatcher so we avoid the
                // client→server hop and can drive a tight synchronous burst.
                Map<String, Object> payload = Map.of("v", 1);
                for (int i = 0; i < 50; i++) {
                    bootstrap.topicDispatcher().publish("/non-reliable", "overflow-reject", payload);
                    bootstrap.topicDispatcher().publish("/non-reliable", "overflow-drop", payload);
                    bootstrap.topicDispatcher().publish("/non-reliable", "overflow-coalesce", payload);
                }

                HttpClient httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                HttpRequest scrapeRequest = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + managementPort + "/metrics"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                    HttpResponse<String> response = httpClient.send(scrapeRequest, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    String body = response.body();
                    assertThat(body).contains("wsserver_queue_overflow_total");
                    assertThat(body).contains("topic=\"overflow-reject\"");
                    assertThat(containsNonZeroCounter(body, "wsserver_queue_overflow_total", "overflow-reject"))
                            .as("REJECT_NEW overflow counter should be > 0 for overflow-reject")
                            .isTrue();
                    assertThat(containsNonZeroCounter(body, "wsserver_messages_dropped_total", "overflow-drop"))
                            .as("DROP_OLDEST dropped counter should be > 0 for overflow-drop")
                            .isTrue();
                    assertThat(containsNonZeroCounter(body, "wsserver_messages_coalesced_total", "overflow-coalesce"))
                            .as("COALESCE counter should be > 0 for overflow-coalesce")
                            .isTrue();
                });
            } finally {
                socket.disconnect();
                socket.close();
            }
        }
    }

    private static boolean containsNonZeroCounter(String body, String metricName, String topic) {
        for (String line : body.split("\n")) {
            if (line.startsWith("#") || !line.startsWith(metricName)) {
                continue;
            }
            if (!line.contains("topic=\"" + topic + "\"")) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            if (space < 0) {
                continue;
            }
            try {
                double value = Double.parseDouble(line.substring(space + 1).trim());
                if (value > 0.0) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // not a numeric sample line, skip
            }
        }
        return false;
    }

    private static JSONObject subscribeFor(String topic) {
        try {
            return new JSONObject().put("topic", topic);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int nextPort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}

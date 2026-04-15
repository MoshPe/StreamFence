package io.streamfence.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.streamfence.DeliveryMode;
import io.streamfence.NamespaceSpec;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServer;
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end verification that {@link OverflowAction#SPILL_TO_DISK} works
 * correctly through a real running server:
 * <ol>
 *   <li>Messages overflow the tiny in-memory queue and are spilled to disk.</li>
 *   <li>When the client drains the queue all messages arrive in FIFO order.</li>
 *   <li>{@code ServerMetrics} records spill events
 *       ({@code wsserver_messages_spilled_total > 0}).</li>
 *   <li>After client disconnect the spill directory is cleaned up.</li>
 * </ol>
 */
@Tag("slow")
class SpillToDiskIntegrationTest {

    private static final String NAMESPACE = "/feed";
    private static final String TOPIC     = "snapshot";
    private static final int    TOTAL_MESSAGES = 10;

    @Test
    void spillToDiskDeliversAllMessagesInFifoOrder(@TempDir Path tempDir) throws Exception {
        int port = nextPort();

        // maxQueuedMessagesPerClient=2 so that messages 3..10 spill to disk.
        try (SocketIoServer server = SocketIoServer.builder()
                .host("127.0.0.1")
                .port(port)
                .spillRootPath(tempDir.toString())
                .namespace(NamespaceSpec.builder(NAMESPACE)
                        .topic(TOPIC)
                        .deliveryMode(DeliveryMode.BEST_EFFORT)
                        .overflowAction(OverflowAction.SPILL_TO_DISK)
                        .maxQueuedMessagesPerClient(2)
                        .maxQueuedBytesPerClient(524_288)
                        .ackTimeoutMs(1_000)
                        .maxRetries(0)
                        .build())
                .buildServer()) {

            server.start();

            // --- 1. Connect the client but DON'T drain yet -----------------
            AtomicBoolean readyToDrain = new AtomicBoolean(false);
            CountDownLatch subscribedLatch = new CountDownLatch(1);
            List<Map<?, ?>> received = new CopyOnWriteArrayList<>();

            IO.Options options = IO.Options.builder()
                    .setForceNew(true)
                    .setReconnection(false)
                    .setTransports(new String[]{"websocket"})
                    .setTimeout(5_000)
                    .build();

            Socket socket = IO.socket("http://127.0.0.1:" + port + NAMESPACE, options);
            try {
                socket.on(Socket.EVENT_CONNECT, args ->
                        socket.emit("subscribe", subscribeFor(TOPIC)));
                socket.on("subscribed", args -> subscribedLatch.countDown());

                // Buffer incoming messages; the handler is intentionally cheap
                // so we don't introduce artificial drain delay inside the test.
                socket.on("topic-message", args -> {
                    if (args.length > 0 && args[0] instanceof JSONObject json) {
                        try {
                            Object payload = json.has("payload") ? json.get("payload") : null;
                            if (payload instanceof JSONObject p) {
                                received.add(Map.of("seq", p.optInt("seq", -1)));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });

                socket.connect();
                assertThat(subscribedLatch.await(10, TimeUnit.SECONDS))
                        .as("client should subscribe within 10 s")
                        .isTrue();

                // --- 2. Publish TOTAL_MESSAGES messages synchronously ------
                // The in-memory queue holds 2; the rest (8) must spill.
                for (int seq = 1; seq <= TOTAL_MESSAGES; seq++) {
                    server.publish(NAMESPACE, TOPIC, Map.of("seq", seq));
                }

                // --- 3. Wait for all messages to arrive --------------------
                // Spill drain happens automatically as the in-memory queue
                // empties — no explicit trigger needed.
                await().atMost(15, TimeUnit.SECONDS)
                        .pollInterval(100, TimeUnit.MILLISECONDS)
                        .until(() -> received.size() >= TOTAL_MESSAGES);

                assertThat(received).hasSize(TOTAL_MESSAGES);

                // --- 4. Verify FIFO order -----------------------------------
                List<Integer> seqs = new ArrayList<>();
                for (Map<?, ?> msg : received) {
                    seqs.add((Integer) msg.get("seq"));
                }
                List<Integer> expected = new ArrayList<>();
                for (int i = 1; i <= TOTAL_MESSAGES; i++) {
                    expected.add(i);
                }
                assertThat(seqs)
                        .as("messages must arrive in FIFO (publish) order")
                        .isEqualTo(expected);

                // --- 5. Verify spill metric ---------------------------------
                String scrape = server.metrics().scrape();
                assertThat(scrape)
                        .as("spill counter must appear in Prometheus scrape")
                        .contains("wsserver_messages_spilled_total");
                assertThat(containsNonZeroCounter(scrape, "wsserver_messages_spilled_total", TOPIC))
                        .as("wsserver_messages_spilled_total for topic=" + TOPIC + " must be > 0")
                        .isTrue();

            } finally {
                socket.disconnect();
                socket.close();
            }

            // --- 6. After disconnect verify spill directory is cleaned up --
            // Give the disconnect handler a moment to run cleanup.
            await().atMost(5, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        if (Files.exists(tempDir)) {
                            long spillFiles = countSpillFiles(tempDir);
                            assertThat(spillFiles)
                                    .as("spill files under %s must be cleaned up after disconnect", tempDir)
                                    .isZero();
                        }
                    });
        }
    }

    // -----------------------------------------------------------------------

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

    private static long countSpillFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".spill") || name.endsWith(".tmp");
                    })
                    .count();
        }
    }

    private static JSONObject subscribeFor(String topic) {
        try {
            return new JSONObject().put("topic", topic);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int nextPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}

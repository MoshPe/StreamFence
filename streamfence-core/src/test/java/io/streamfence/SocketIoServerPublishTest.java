package io.streamfence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SocketIoServerPublishTest {

    @Test
    void broadcastsPublishToEverySubscriber() throws Exception {
        int port = nextPort();
        RecordingListener listener = new RecordingListener();
        try (SocketIoServer server = buildServer(port, listener)) {
            server.start();

            TrackingClient alice = connectAndSubscribe(port);
            TrackingClient bob = connectAndSubscribe(port);

            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> listener.subscribes.size() >= 2);

            server.publish("/feed", "prices", Map.of("value", 42));

            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertThat(alice.lastMessage.get()).isNotNull();
                        assertThat(bob.lastMessage.get()).isNotNull();
                    });

            assertThat(alice.lastMessage.get().getJSONObject("metadata").getString("topic"))
                    .isEqualTo("prices");
            assertThat(bob.lastMessage.get().getJSONObject("metadata").getString("topic"))
                    .isEqualTo("prices");

            alice.close();
            bob.close();
        }
    }

    @Test
    void publishToDeliversOnlyToTargetedClient() throws Exception {
        int port = nextPort();
        RecordingListener listener = new RecordingListener();
        try (SocketIoServer server = buildServer(port, listener)) {
            server.start();

            TrackingClient alice = connectAndSubscribe(port);
            TrackingClient bob = connectAndSubscribe(port);

            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> listener.subscribes.size() >= 2);

            // The first client to subscribe is alice — target only that session.
            String aliceServerId = listener.subscribes.get(0).clientId();
            server.publishTo("/feed", aliceServerId, "prices", Map.of("value", 99));

            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(alice.lastMessage.get()).isNotNull());

            // Give bob a chance to (not) receive.
            Thread.sleep(500);
            assertThat(bob.lastMessage.get()).isNull();

            alice.close();
            bob.close();
        }
    }

    @Test
    void publishToUnknownClientIsSilentNoOp() throws Exception {
        int port = nextPort();
        try (SocketIoServer server = buildServer(port, new RecordingListener())) {
            server.start();
            server.publishTo("/feed", "client-that-does-not-exist", "prices", Map.of("value", 1));
        }
    }

    @Test
    void publishWithUnknownNamespaceThrows() throws Exception {
        int port = nextPort();
        try (SocketIoServer server = buildServer(port, new RecordingListener())) {
            server.start();
            assertThatThrownBy(() -> server.publish("/ghost", "prices", Map.of("x", 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static SocketIoServer buildServer(int port, ServerEventListener listener) {
        return SocketIoServer.builder()
                .host("127.0.0.1")
                .port(port)
                .transportMode(TransportMode.WS)
                .authMode(AuthMode.NONE)
                .listener(listener)
                .namespace(NamespaceSpec.builder("/feed")
                        .authRequired(false)
                        .deliveryMode(DeliveryMode.BEST_EFFORT)
                        .overflowAction(OverflowAction.REJECT_NEW)
                        .maxQueuedMessagesPerClient(16)
                        .maxQueuedBytesPerClient(65_536)
                        .ackTimeoutMs(1_000)
                        .maxRetries(0)
                        .coalesce(false)
                        .allowPolling(true)
                        .maxInFlight(1)
                        .topics(List.of("prices"))
                        .build())
                .buildServer();
    }

    private static TrackingClient connectAndSubscribe(int port) throws Exception {
        IO.Options options = IO.Options.builder()
                .setForceNew(true)
                .setReconnection(false)
                .setTransports(new String[]{"websocket"})
                .setTimeout(5000)
                .build();
        Socket socket = IO.socket("http://127.0.0.1:" + port + "/feed", options);
        TrackingClient tracking = new TrackingClient(socket);
        CountDownLatch subscribedLatch = new CountDownLatch(1);
        socket.on("subscribed", args -> subscribedLatch.countDown());
        socket.on("topic-message", args -> tracking.lastMessage.set((JSONObject) args[0]));
        socket.on(Socket.EVENT_CONNECT, args -> {
            try {
                socket.emit("subscribe", new JSONObject().put("topic", "prices"));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        socket.connect();
        assertThat(subscribedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        return tracking;
    }

    private static int nextPort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private static final class RecordingListener implements ServerEventListener {
        private final List<ServerEventListener.SubscribedEvent> subscribes = new CopyOnWriteArrayList<>();

        @Override
        public void onSubscribed(SubscribedEvent event) {
            subscribes.add(event);
        }
    }

    private static final class TrackingClient implements AutoCloseable {
        private final Socket socket;
        private final AtomicReference<JSONObject> lastMessage = new AtomicReference<>();

        TrackingClient(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void close() {
            socket.disconnect();
            socket.close();
        }
    }
}

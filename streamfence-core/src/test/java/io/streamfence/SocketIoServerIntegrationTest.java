package io.streamfence;

import static org.assertj.core.api.Assertions.assertThat;

import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SocketIoServerIntegrationTest {

    @Test
    void codeFirstServerStartsAndEmitsLifecycleEvents() throws Exception {
        int port = nextPort();
        RecordingListener listener = new RecordingListener();

        try (SocketIoServer server = SocketIoServer.builder()
                .host("127.0.0.1")
                .port(port)
                .transportMode(TransportMode.WS)
                .authMode(AuthMode.NONE)
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
                .listener(listener)
                .buildServer()) {
            server.start();

            CountDownLatch messageLatch = new CountDownLatch(1);
            JSONObject[] received = new JSONObject[1];

            IO.Options options = IO.Options.builder()
                    .setForceNew(true)
                    .setReconnection(false)
                    .setTransports(new String[]{"websocket"})
                    .setTimeout(5000)
                    .build();
            Socket socket = IO.socket("http://127.0.0.1:" + port + "/feed", options);
            socket.on("subscribed", args -> socket.emit("publish", publishPayload()));
            socket.on("topic-message", args -> {
                received[0] = (JSONObject) args[0];
                messageLatch.countDown();
            });
            socket.on(Socket.EVENT_CONNECT, args -> socket.emit("subscribe", subscribePayload()));

            socket.connect();
            assertThat(messageLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(received[0].getJSONObject("metadata").getString("topic")).isEqualTo("prices");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertThat(listener.started()).isTrue();
                        assertThat(listener.connected()).isTrue();
                        assertThat(listener.subscribed()).isTrue();
                        assertThat(listener.publishAccepted()).isTrue();
                    });

            socket.disconnect();
            socket.close();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(listener.disconnected()).isTrue());
        }

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(listener.stopped()).isTrue());
    }

    private static JSONObject subscribePayload() {
        try {
            return new JSONObject().put("topic", "prices");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JSONObject publishPayload() {
        try {
            return new JSONObject()
                    .put("topic", "prices")
                    .put("payload", new JSONObject().put("value", 42));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int nextPort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private static final class RecordingListener implements ServerEventListener {
        private volatile boolean started;
        private volatile boolean connected;
        private volatile boolean subscribed;
        private volatile boolean publishAccepted;
        private volatile boolean disconnected;
        private volatile boolean stopped;

        @Override
        public void onServerStarted(ServerEventListener.ServerStartedEvent event) {
            started = true;
        }

        @Override
        public void onClientConnected(ServerEventListener.ClientConnectedEvent event) {
            connected = true;
        }

        @Override
        public void onSubscribed(ServerEventListener.SubscribedEvent event) {
            subscribed = true;
        }

        @Override
        public void onPublishAccepted(ServerEventListener.PublishAcceptedEvent event) {
            publishAccepted = true;
        }

        @Override
        public void onClientDisconnected(ServerEventListener.ClientDisconnectedEvent event) {
            disconnected = true;
        }

        @Override
        public void onServerStopped(ServerEventListener.ServerStoppedEvent event) {
            stopped = true;
        }

        boolean started() {
            return started;
        }

        boolean connected() {
            return connected;
        }

        boolean subscribed() {
            return subscribed;
        }

        boolean publishAccepted() {
            return publishAccepted;
        }

        boolean disconnected() {
            return disconnected;
        }

        boolean stopped() {
            return stopped;
        }
    }
}

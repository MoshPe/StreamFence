package io.streamfence.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class BulkNamespaceIntegrationTest {

    @Test
    void bulkNamespaceDeliversPublishedEnvelopeToSubscriber() throws Exception {
        int port = nextPort();
        String spillRootPath = io.streamfence.SocketIoServerSpec.DEFAULT_SPILL_ROOT_PATH;
        ServerConfig config = new ServerConfig(
                "127.0.0.1",
                port,
                TransportMode.WS,
                null,
                15000,
                30000,
                6291456,
                6291456,
                false,
                false,
                null,
                AuthMode.NONE,
                Map.of(),
                Map.of(
                        "/non-reliable", new NamespaceConfig(false),
                        "/reliable", new NamespaceConfig(false),
                        "/bulk", new NamespaceConfig(false)
                ),
                List.of(new TopicPolicy(
                        "/bulk",
                        "blob",
                        DeliveryMode.BEST_EFFORT,
                        OverflowAction.REJECT_NEW,
                        16,
                        65536,
                        1000,
                        0,
                        false,
                        false,
                        false,
                        1)),
                "0.0.0.0",
                0,
                10_000,
                0,
                60_000,
                20,
                spillRootPath
        );

        CountDownLatch subscribedLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        JSONObject[] received = new JSONObject[1];

        try (SocketServerBootstrap bootstrap = new SocketServerBootstrap(config)) {
            bootstrap.start();

            IO.Options options = IO.Options.builder()
                    .setForceNew(true)
                    .setReconnection(false)
                    .setTransports(new String[]{"websocket"})
                    .setTimeout(5000)
                    .build();
            Socket socket = IO.socket("http://127.0.0.1:" + port + "/bulk", options);
            socket.on("subscribed", args -> socket.emit("publish", publishPayload()));
            socket.on("topic-message", args -> {
                try {
                    received[0] = (JSONObject) args[0];
                    messageLatch.countDown();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            socket.on(Socket.EVENT_CONNECT, args -> {
                socket.emit("subscribe", subscribePayload());
                subscribedLatch.countDown();
            });

            socket.connect();
            assertThat(subscribedLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(messageLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(received[0].getJSONObject("metadata").getString("topic")).isEqualTo("blob");
            assertThat(received[0].getJSONObject("payload").getString("blob")).isEqualTo("AQIDBA==");
            socket.disconnect();
            socket.close();
        }
    }

    private static JSONObject subscribePayload() {
        try {
            return new JSONObject().put("topic", "blob");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JSONObject publishPayload() {
        try {
            return new JSONObject()
                    .put("topic", "blob")
                    .put("payload", new JSONObject().put("blob", "AQIDBA=="));
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



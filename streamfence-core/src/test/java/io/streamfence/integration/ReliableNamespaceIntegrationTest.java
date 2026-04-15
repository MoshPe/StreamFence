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

class ReliableNamespaceIntegrationTest {

    @Test
    void reliableNamespaceUsesHandshakeTokenOnlyAndDoesNotExposeCmdOrAuthEvents() throws Exception {
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
                AuthMode.TOKEN,
                Map.of("demo-client", "change-me"),
                Map.of(
                        "/non-reliable", new NamespaceConfig(false),
                        "/reliable", new NamespaceConfig(true),
                        "/bulk", new NamespaceConfig(true)
                ),
                List.of(new TopicPolicy(
                        "/reliable",
                        "alerts",
                        DeliveryMode.AT_LEAST_ONCE,
                        OverflowAction.REJECT_NEW,
                        16,
                        65536,
                        1000,
                        1,
                        false,
                        true,
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

        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch subscribedLatch = new CountDownLatch(1);
        CountDownLatch cmdResultLatch = new CountDownLatch(1);
        CountDownLatch authOkLatch = new CountDownLatch(1);

        try (SocketServerBootstrap bootstrap = new SocketServerBootstrap(config)) {
            bootstrap.start();

            IO.Options options = IO.Options.builder()
                    .setForceNew(true)
                    .setReconnection(false)
                    .setTransports(new String[]{"websocket"})
                    .setTimeout(5000)
                    .setQuery("token=change-me")
                    .build();
            Socket socket = IO.socket("http://127.0.0.1:" + port + "/reliable", options);
            socket.on(Socket.EVENT_CONNECT, args -> {
                connectLatch.countDown();
                socket.emit("subscribe", subscribePayload());
                socket.emit("cmd", cmdPayload());
                socket.emit("auth", authPayload());
            });
            socket.on("subscribed", args -> subscribedLatch.countDown());
            socket.on("cmd-result", args -> cmdResultLatch.countDown());
            socket.on("auth-ok", args -> authOkLatch.countDown());

            socket.connect();

            assertThat(connectLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(subscribedLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cmdResultLatch.await(1500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(authOkLatch.await(1500, TimeUnit.MILLISECONDS)).isFalse();

            socket.disconnect();
            socket.close();
        }
    }

    private static JSONObject subscribePayload() {
        try {
            return new JSONObject().put("topic", "alerts");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JSONObject cmdPayload() {
        try {
            return new JSONObject().put("command", "ping");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JSONObject authPayload() {
        try {
            return new JSONObject().put("token", "change-me");
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




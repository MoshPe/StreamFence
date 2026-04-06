package io.streamfence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.*;
import io.streamfence.internal.transport.SocketServerBootstrap;
import io.streamfence.internal.config.NamespaceConfig;
import io.streamfence.internal.config.ServerConfig;
import io.streamfence.TLSConfig;
import io.streamfence.internal.config.TopicPolicy;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;

class WssNamespaceIntegrationTest {

    @Test
    void wssServerAcceptsSecureWebSocketHandshake() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        int port = nextPort();
        ServerConfig config = new ServerConfig(
                "127.0.0.1",
                port,
                TransportMode.WSS,
                new TLSConfig(certificate.certificate().getAbsolutePath(), certificate.privateKey().getAbsolutePath(), null, "changeit"),
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
                        "/non-reliable",
                        "prices",
                        DeliveryMode.BEST_EFFORT,
                        OverflowAction.REJECT_NEW,
                        16,
                        65536,
                        1000,
                        0,
                        false,
                        true,
                        false,
                        1))
        );

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        String[] firstMessage = new String[1];

        try (SocketServerBootstrap bootstrap = new SocketServerBootstrap(config)) {
            bootstrap.start();

            X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();

            Request request = new Request.Builder()
                    .url("wss://127.0.0.1:" + port + "/socket.io/?EIO=4&transport=websocket")
                    .build();
            WebSocket webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    openLatch.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    firstMessage[0] = text;
                    messageLatch.countDown();
                }
            });

            assertThat(openLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(messageLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(firstMessage[0]).startsWith("0{");
            webSocket.close(1000, "done");
            okHttpClient.dispatcher().executorService().shutdownNow();
            okHttpClient.connectionPool().evictAll();
        }
    }

    private static int nextPort() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}




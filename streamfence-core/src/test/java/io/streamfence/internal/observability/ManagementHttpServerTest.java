package io.streamfence.internal.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.ServerMetrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ManagementHttpServerTest {

    @Test
    void servesPrometheusScrapeOnMetricsEndpoint() throws Exception {
        ServerMetrics metrics = new ServerMetrics();
        metrics.recordConnect("/non-reliable");
        metrics.recordPublish("/non-reliable", "prices", 128);
        metrics.recordReceived("/non-reliable", "prices", 96);

        ManagementHttpServer server = new ManagementHttpServer(metrics, "127.0.0.1", 0);
        try {
            server.start();
            int port = server.boundPort();
            assertThat(port).isPositive();

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .get()
                    .asString()
                    .startsWith("text/plain");
            assertThat(response.body()).contains("wsserver_connections_opened_total");
            assertThat(response.body()).contains("wsserver_messages_published_total");
            assertThat(response.body()).contains("wsserver_messages_received_total");
            assertThat(response.body()).contains("wsserver_bytes_received_total");
            assertThat(response.body()).contains("jvm_memory_used_bytes");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesInboundTrafficCountersPerNamespaceAndTopic() throws Exception {
        ServerMetrics metrics = new ServerMetrics();
        metrics.recordReceived("/reliable", "alerts", 256);
        metrics.recordReceived("/reliable", "alerts", 128);

        ManagementHttpServer server = new ManagementHttpServer(metrics, "127.0.0.1", 0);
        try {
            server.start();
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + "/metrics"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("wsserver_messages_received_total{namespace=\"/reliable\",topic=\"alerts\"} 2.0");
            assertThat(response.body()).contains("wsserver_bytes_received_total{namespace=\"/reliable\",topic=\"alerts\"} 384.0");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonGetMethods() throws Exception {
        ServerMetrics metrics = new ServerMetrics();
        ManagementHttpServer server = new ManagementHttpServer(metrics, "127.0.0.1", 0);
        try {
            server.start();
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + "/metrics"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(405);
        } finally {
            server.stop(0);
        }
    }
}

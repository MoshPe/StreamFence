package io.streamfence.demo.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.streamfence.demo.dashboard.DashboardSnapshot;
import io.streamfence.demo.dashboard.NamespaceSnapshot;
import io.streamfence.demo.runtime.DemoPresetRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoConsoleServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void servesTheConsoleAndDemoConfig() throws Exception {
        try (DemoConsoleServer server = DemoConsoleServer.create(
                0,
                () -> URI.create("http://127.0.0.1:9092"),
                () -> URI.create("http://127.0.0.1:9093"),
                () -> DemoPresetRegistry.require("throughput"),
                () -> new DashboardSnapshot(
                        "throughput",
                        24,
                        480,
                        120.0d,
                        4096.0d,
                        2048.0d,
                        List.of(new NamespaceSnapshot(
                                "/non-reliable",
                                24,
                                320,
                                4096,
                                160,
                                2048,
                                0,
                                0,
                                0,
                                8))),
                DemoActionController.noop())) {
            server.start();

            HttpResponse index = get(server.baseUri().resolve("/"));
            assertThat(index.status).isEqualTo(200);
            assertThat(index.contentType).contains("text/html");
            assertThat(index.body).contains("Runtime Summary");
            assertThat(index.body).contains("Live Timeline");
            assertThat(index.body).contains("Payload Metadata");
            assertThat(index.body).contains("Preset Deep Dive");
            assertThat(index.body).contains("Manual Client Controls");

            HttpResponse events = probe(server.baseUri().resolve("/events"));
            assertThat(events.status).isEqualTo(200);
            assertThat(events.contentType).contains("text/event-stream");

            HttpResponse config = get(server.baseUri().resolve("/api/demo-config"));
            assertThat(config.status).isEqualTo(200);
            assertThat(config.contentType).contains("application/json");

            JsonNode root = MAPPER.readTree(config.body);
            assertThat(root.get("tokenHint").asText()).isEqualTo("change-me");
            assertThat(root.get("socketUrl").asText()).isEqualTo("http://127.0.0.1:9092");
            assertThat(root.get("metricsUrl").asText()).isEqualTo("http://127.0.0.1:9093");
            assertThat(root.get("activePreset").asText()).isEqualTo("throughput");
            assertThat(root.get("presets")).hasSize(5);
            List<Map<String, Object>> personas = MAPPER.convertValue(root.get("personas"), new TypeReference<List<Map<String, Object>>>() { });
            assertThat(personas).extracting(persona -> persona.get("name"))
                    .containsExactly("price-bot");
            List<Map<String, Object>> namespaces = MAPPER.convertValue(root.get("namespaces"), new TypeReference<List<Map<String, Object>>>() { });
            assertThat(namespaces).extracting(namespace -> namespace.get("namespace"))
                    .containsExactly("/non-reliable");

            HttpResponse dashboard = get(server.baseUri().resolve("/api/dashboard"));
            assertThat(dashboard.status).isEqualTo(200);
            JsonNode dashboardRoot = MAPPER.readTree(dashboard.body);
            assertThat(dashboardRoot.get("presetId").asText()).isEqualTo("throughput");
            assertThat(dashboardRoot.get("activeClients").asLong()).isEqualTo(24);
            assertThat(dashboardRoot.get("messagesPerSecond").asDouble()).isEqualTo(120.0d);
        }
    }

    @Test
    void rejectsNonPostActionMethods() throws Exception {
        try (DemoConsoleServer server = DemoConsoleServer.create(
                0,
                () -> URI.create("http://127.0.0.1:9092"),
                () -> URI.create("http://127.0.0.1:9093"),
                () -> DemoPresetRegistry.require("throughput"),
                () -> new DashboardSnapshot("throughput", 0, 0, 0.0d, 0.0d, 0.0d, List.of()),
                DemoActionController.noop())) {
            server.start();

            HttpResponse response = get(server.baseUri().resolve("/api/actions/broadcast-sample"));

            assertThat(response.status).isEqualTo(405);
            assertThat(response.contentType).contains("application/json");
        }
    }

    @Test
    void servesFrontendThatListensToNamedPayloadEvents() throws Exception {
        try (DemoConsoleServer server = DemoConsoleServer.create(
                0,
                () -> URI.create("http://127.0.0.1:9092"),
                () -> URI.create("http://127.0.0.1:9093"),
                () -> DemoPresetRegistry.require("throughput"),
                () -> new DashboardSnapshot("throughput", 0, 0, 0.0d, 0.0d, 0.0d, List.of()),
                DemoActionController.noop())) {
            server.start();

            HttpResponse script = get(server.baseUri().resolve("/demo/app.js"));

            assertThat(script.status).isEqualTo(200);
            assertThat(script.contentType).contains("application/javascript");
            assertThat(script.body).contains("const STREAM_EVENTS = [");
            assertThat(script.body).contains("\"publish\"");
            assertThat(script.body).contains("\"topic_message\"");
            assertThat(script.body).contains("const HIGH_FREQUENCY_CATEGORIES = new Set([\"publish\", \"topic_message\"]);");
            assertThat(script.body).contains("state.source.addEventListener(eventName, handleStreamEvent);");
        }
    }

    @Test
    void servesFrontendWithScrollableFiftyEventTimeline() throws Exception {
        try (DemoConsoleServer server = DemoConsoleServer.create(
                0,
                () -> URI.create("http://127.0.0.1:9092"),
                () -> URI.create("http://127.0.0.1:9093"),
                () -> DemoPresetRegistry.require("throughput"),
                () -> new DashboardSnapshot("throughput", 0, 0, 0.0d, 0.0d, 0.0d, List.of()),
                DemoActionController.noop())) {
            server.start();

            HttpResponse script = get(server.baseUri().resolve("/demo/app.js"));
            HttpResponse stylesheet = get(server.baseUri().resolve("/demo/styles.css"));

            assertThat(script.status).isEqualTo(200);
            assertThat(script.body).contains("const MAX_EVENTS = 50;");
            assertThat(stylesheet.status).isEqualTo(200);
            assertThat(stylesheet.body).contains("overflow-y: auto");
        }
    }

    private static HttpResponse get(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        connection.setRequestProperty("Accept", "*/*");
        int status = connection.getResponseCode();
        String contentType = connection.getContentType();
        String body = readBody(connection, status);
        connection.disconnect();
        return new HttpResponse(status, contentType, body);
    }

    private static HttpResponse probe(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        connection.setRequestProperty("Accept", "text/event-stream");
        int status = connection.getResponseCode();
        String contentType = connection.getContentType();
        connection.disconnect();
        return new HttpResponse(status, contentType, "");
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record HttpResponse(int status, String contentType, String body) {
    }
}

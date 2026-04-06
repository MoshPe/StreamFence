package io.streamfence.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.streamfence.demo.launcher.DemoLauncherMain;
import io.streamfence.demo.launcher.DemoLauncherMain.DemoLauncherHandle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LauncherSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void launcherStartsConsoleServesConfigDashboardAndStreamsEvents() throws Exception {
        try (DemoLauncherHandle handle = DemoLauncherMain.startForTest(new String[] {
                "--server-port=0",
                "--management-port=0",
                "--console-port=0"
        })) {
            URI consoleUri = handle.consoleUri();

            HttpResponse index = get(consoleUri.resolve("/"));
            assertThat(index.status()).isEqualTo(200);
            assertThat(index.body()).contains("Live Timeline");

            HttpResponse config = get(consoleUri.resolve("/api/demo-config"));
            assertThat(config.status()).isEqualTo(200);

            JsonNode root = MAPPER.readTree(config.body());
            assertThat(root.get("socketUrl").asText()).startsWith("http://127.0.0.1:");
            assertThat(root.get("metricsUrl").asText()).startsWith("http://127.0.0.1:");
            assertThat(root.get("activePreset").asText()).isEqualTo("throughput");
            assertThat(root.get("personas")).hasSize(1);

            JsonNode dashboard = awaitDashboard(consoleUri, snapshot ->
                    snapshot.get("activeClients").asLong() >= 24
                            && snapshot.get("totalMessages").asLong() > 0);
            assertThat(dashboard.get("presetId").asText()).isEqualTo("throughput");
            assertThat(dashboard.get("messagesPerSecond").asDouble()).isPositive();

            String sseChunk = readSseChunkContaining(consoleUri.resolve("/events"), "server_ready");
            assertThat(sseChunk).contains("event:");
            assertThat(sseChunk).contains("server_ready");
        }
    }

    @Test
    void launcherAppliesPresetsAndSurfacesPressureSignals() throws Exception {
        try (DemoLauncherHandle handle = DemoLauncherMain.startForTest(new String[] {
                "--server-port=0",
                "--management-port=0",
                "--console-port=0"
        })) {
            URI consoleUri = handle.consoleUri();

            HttpResponse bulkResponse = post(consoleUri.resolve("/api/actions/apply-preset"), "{\"presetId\":\"bulk\"}");
            assertThat(bulkResponse.status()).isEqualTo(202);

            JsonNode bulkDashboard = awaitDashboard(consoleUri, snapshot ->
                    "bulk".equals(snapshot.get("presetId").asText())
                            && snapshot.get("activeClients").asLong() >= 4
                            && snapshot.get("totalMessages").asLong() > 0);
            assertThat(bulkDashboard.get("presetId").asText()).isEqualTo("bulk");

            HttpResponse reliableResponse = post(consoleUri.resolve("/api/actions/apply-preset"), "{\"presetId\":\"reliable\"}");
            assertThat(reliableResponse.status()).isEqualTo(202);

            JsonNode reliableDashboard = awaitDashboard(consoleUri, snapshot ->
                    "reliable".equals(snapshot.get("presetId").asText())
                            && snapshot.get("activeClients").asLong() >= 16
                            && snapshot.get("totalMessages").asLong() > 0);
            assertThat(reliableDashboard.get("presetId").asText()).isEqualTo("reliable");
            assertThat(reliableDashboard.get("messagesPerSecond").asDouble()).isPositive();

            HttpResponse pressureResponse = post(consoleUri.resolve("/api/actions/apply-preset"), "{\"presetId\":\"pressure\"}");
            assertThat(pressureResponse.status()).isEqualTo(202);

            JsonNode pressureDashboard = awaitDashboard(consoleUri, snapshot ->
                    "pressure".equals(snapshot.get("presetId").asText())
                            && snapshot.get("totalMessages").asLong() > 0
                            && (snapshot.get("retryCount").asLong() > 0
                            || snapshot.get("queueOverflow").asLong() > 0
                            || snapshot.get("coalesced").asLong() > 0
                            || snapshot.get("dropped").asLong() > 0));
            assertThat(pressureDashboard.get("presetId").asText()).isEqualTo("pressure");
        }
    }

    @Test
    void closingLauncherReleasesServerAndConsolePorts() throws Exception {
        int serverPort = reservePort();
        int managementPort = reservePort();
        int consolePort = reservePort();

        DemoLauncherHandle handle = DemoLauncherMain.startForTest(new String[] {
                "--server-port=" + serverPort,
                "--management-port=" + managementPort,
                "--console-port=" + consolePort
        });

        handle.close();
        Thread.sleep(1_000L);

        assertThat(canBind(serverPort)).isTrue();
        assertThat(canBind(managementPort)).isTrue();
        assertThat(canBind(consolePort)).isTrue();
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

    private static HttpResponse post(URI uri, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        String contentType = connection.getContentType();
        String responseBody = readBody(connection, status);
        connection.disconnect();
        return new HttpResponse(status, contentType, responseBody);
    }

    private static JsonNode awaitDashboard(URI consoleUri, java.util.function.Predicate<JsonNode> predicate) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            HttpResponse response = get(consoleUri.resolve("/api/dashboard"));
            assertThat(response.status()).isEqualTo(200);
            latest = MAPPER.readTree(response.body());
            if (predicate.test(latest)) {
                return latest;
            }
            Thread.sleep(500L);
        }
        return latest;
    }

    private static String readSseChunkContaining(URI uri, String needle) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.connect();
        try (InputStream in = connection.getInputStream();
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                BufferedReader buffered = new BufferedReader(reader)) {
            StringBuilder chunk = new StringBuilder();
            String line;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && (line = buffered.readLine()) != null) {
                chunk.append(line).append('\n');
                if (chunk.indexOf(needle) >= 0) {
                    return chunk.toString();
                }
            }
            return chunk.toString();
        } finally {
            connection.disconnect();
        }
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

    private static int reservePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private record HttpResponse(int status, String contentType, String body) {
    }
}

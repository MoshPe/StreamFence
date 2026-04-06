package io.streamfence.demo.console;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.streamfence.NamespaceSpec;
import io.streamfence.SocketIoServerSpec;
import io.streamfence.demo.dashboard.DashboardSnapshot;
import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoPayloadKind;
import io.streamfence.demo.runtime.DemoPersona;
import io.streamfence.demo.runtime.DemoPresetDefinition;
import io.streamfence.demo.runtime.DemoPresetRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class DemoConsoleServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int RECENT_EVENT_LIMIT = 2_048;

    private final int port;
    private final Supplier<URI> socketUrlSupplier;
    private final Supplier<URI> metricsUrlSupplier;
    private final Supplier<DemoPresetDefinition> activePresetSupplier;
    private final Supplier<DashboardSnapshot> dashboardSupplier;
    private final DemoActionController actionController;
    private final CopyOnWriteArrayList<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private final ArrayDeque<DemoEvent> recentEvents = new ArrayDeque<>();
    private final Object recentEventsLock = new Object();
    private final AtomicLong eventSequence = new AtomicLong();
    private final ExecutorService executor;
    private HttpServer server;

    private DemoConsoleServer(
            int port,
            Supplier<URI> socketUrlSupplier,
            Supplier<URI> metricsUrlSupplier,
            Supplier<DemoPresetDefinition> activePresetSupplier,
            Supplier<DashboardSnapshot> dashboardSupplier,
            DemoActionController actionController) {
        this.port = port;
        this.socketUrlSupplier = Objects.requireNonNull(socketUrlSupplier, "socketUrlSupplier");
        this.metricsUrlSupplier = Objects.requireNonNull(metricsUrlSupplier, "metricsUrlSupplier");
        this.activePresetSupplier = Objects.requireNonNull(activePresetSupplier, "activePresetSupplier");
        this.dashboardSupplier = Objects.requireNonNull(dashboardSupplier, "dashboardSupplier");
        this.actionController = Objects.requireNonNull(actionController, "actionController");
        this.executor = Executors.newCachedThreadPool(threadFactory());
    }

    public static DemoConsoleServer create(
            int port,
            Supplier<URI> socketUrlSupplier,
            Supplier<io.streamfence.demo.runtime.DemoScenarioDefinition> scenarioSupplier) {
        return create(
                port,
                socketUrlSupplier,
                () -> URI.create("http://127.0.0.1:9093"),
                () -> DemoPresetRegistry.require(DemoPresetRegistry.defaultPresetId()),
                () -> new DashboardSnapshot(DemoPresetRegistry.defaultPresetId(), 0, 0, 0.0d, 0.0d, 0.0d, List.of()),
                DemoActionController.noop());
    }

    public static DemoConsoleServer create(
            int port,
            Supplier<URI> socketUrlSupplier,
            Supplier<URI> metricsUrlSupplier,
            Supplier<DemoPresetDefinition> activePresetSupplier,
            Supplier<DashboardSnapshot> dashboardSupplier,
            DemoActionController actionController) {
        return new DemoConsoleServer(
                port,
                socketUrlSupplier,
                metricsUrlSupplier,
                activePresetSupplier,
                dashboardSupplier,
                actionController);
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create console server", exception);
        }
        server.createContext("/", new StaticHandler("/demo/index.html", "text/html; charset=utf-8"));
        server.createContext("/index.html", new StaticHandler("/demo/index.html", "text/html; charset=utf-8"));
        server.createContext("/demo/index.html", new StaticHandler("/demo/index.html", "text/html; charset=utf-8"));
        server.createContext("/demo/styles.css", new StaticHandler("/demo/styles.css", "text/css; charset=utf-8"));
        server.createContext("/demo/app.js", new StaticHandler("/demo/app.js", "application/javascript; charset=utf-8"));
        server.createContext("/api/demo-config", this::handleDemoConfig);
        server.createContext("/api/dashboard", this::handleDashboard);
        server.createContext("/events", this::handleEvents);
        server.createContext("/api/actions", this::handleAction);
        server.createContext("/api/actions/", this::handleAction);
        server.setExecutor(executor);
        server.start();
    }

    public URI baseUri() {
        ensureStarted();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void publish(DemoEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (recentEventsLock) {
            if (recentEvents.size() == RECENT_EVENT_LIMIT) {
                recentEvents.removeFirst();
            }
            recentEvents.addLast(event);
        }
        String json = toJson(event);
        long id = eventSequence.incrementAndGet();
        for (SseClient client : sseClients) {
            client.send(id, event.category(), json);
        }
    }

    @Override
    public synchronized void close() {
        if (server == null) {
            executor.shutdownNow();
            return;
        }
        for (SseClient client : sseClients) {
            client.close();
        }
        sseClients.clear();
        server.stop(0);
        executor.shutdownNow();
        server = null;
    }

    private void handleDemoConfig(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, mapOf("error", "method-not-allowed"));
            return;
        }
        DemoPresetDefinition activePreset = activePresetSupplier.get();
        SocketIoServerSpec presetSpec = SocketIoServerSpec.fromClasspath(activePreset.configResource());
        List<Map<String, Object>> personas = activePreset.personas().stream().map(this::personaConfig).toList();
        Map<String, NamespaceSpec> namespacesByPath = presetSpec.namespaces().stream()
                .collect(java.util.stream.Collectors.toMap(NamespaceSpec::path, namespace -> namespace));
        List<Map<String, Object>> namespaces = activePreset.personas().stream()
                .map(DemoPersona::namespace)
                .distinct()
                .map(namespace -> namespaceConfig(namespacesByPath.get(namespace)))
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("socketUrl", socketUrlSupplier.get().toString());
        response.put("metricsUrl", metricsUrlSupplier.get().toString());
        response.put("tokenHint", "change-me");
        response.put("activePreset", activePreset.id());
        response.put("activePresetLabel", activePreset.label());
        response.put("activePresetDescription", activePreset.description());
        response.put("transportMode", presetSpec.transportMode().name());
        response.put("authMode", presetSpec.authMode().name());
        response.put("presets", DemoPresetRegistry.supportedPresets().stream().map(this::presetConfig).toList());
        response.put("personas", personas);
        response.put("namespaces", namespaces);
        response.put("lastUpdated", Instant.now().toString());
        respondJson(exchange, 200, response);
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, mapOf("error", "method-not-allowed"));
            return;
        }
        respondJson(exchange, 200, dashboardSupplier.get());
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(exchange);
        sseClients.add(client);
        client.sendComment("connected");
        List<DemoEvent> snapshot;
        synchronized (recentEventsLock) {
            snapshot = List.copyOf(recentEvents);
        }
        for (DemoEvent event : snapshot) {
            client.send(eventSequence.incrementAndGet(), event.category(), toJson(event));
        }
    }

    private void handleAction(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, mapOf("error", "method-not-allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String actionName = path.startsWith("/api/actions/") ? path.substring("/api/actions/".length()) : "";
        if (actionName.isBlank()) {
            respondJson(exchange, 404, mapOf("status", "unknown-action"));
            return;
        }
        String requestBody = readBody(exchange);
        DemoActionController.Response response = actionController.handle(actionName, requestBody);
        respondRawJson(exchange, response.statusCode(), response.body());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respondJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = toJsonBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void respondRawJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static byte[] toJsonBytes(Object body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize console response", exception);
        }
    }

    private static String toJson(DemoEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize demo event", exception);
        }
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return map;
    }

    private Map<String, Object> presetConfig(DemoPresetDefinition preset) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", preset.id());
        map.put("label", preset.label());
        map.put("description", preset.description());
        return map;
    }

    private Map<String, Object> personaConfig(DemoPersona persona) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", persona.name());
        map.put("namespace", persona.namespace());
        map.put("topics", persona.topics());
        map.put("tokenRequired", persona.tokenRequired());
        map.put("payloadKind", persona.payloadKind().name());
        map.put("publishCadenceMs", persona.publishCadence().toMillis());
        map.put("connectionCount", persona.connectionCount());
        map.put("ackDelayMs", persona.ackDelay().toMillis());
        return map;
    }

    private Map<String, Object> namespaceConfig(NamespaceSpec namespaceSpec) {
        if (namespaceSpec == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("namespace", namespaceSpec.path());
        map.put("topics", namespaceSpec.topics());
        map.put("tokenRequired", namespaceSpec.authRequired());
        map.put("payloadKind", namespaceSpec.path().equals("/bulk") ? DemoPayloadKind.BINARY.name() : DemoPayloadKind.JSON.name());
        map.put("deliveryMode", namespaceSpec.deliveryMode().name());
        map.put("overflowAction", namespaceSpec.overflowAction().name());
        map.put("coalesce", namespaceSpec.coalesce());
        map.put("maxInFlight", namespaceSpec.maxInFlight());
        map.put("maxQueuedMessagesPerClient", namespaceSpec.maxQueuedMessagesPerClient());
        map.put("maxQueuedBytesPerClient", namespaceSpec.maxQueuedBytesPerClient());
        map.put("ackTimeoutMs", namespaceSpec.ackTimeoutMs());
        map.put("maxRetries", namespaceSpec.maxRetries());
        map.put("allowPolling", namespaceSpec.allowPolling());
        return map;
    }

    private static ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "wsserver-demo-console");
            thread.setDaemon(true);
            return thread;
        };
    }

    private void ensureStarted() {
        if (server == null) {
            throw new IllegalStateException("Console server is not started");
        }
    }

    private final class StaticHandler implements HttpHandler {
        private final String resourcePath;
        private final String contentType;

        private StaticHandler(String resourcePath, String contentType) {
            this.resourcePath = resourcePath;
            this.contentType = contentType;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            byte[] bytes = readResource(resourcePath);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } finally {
                exchange.close();
            }
        }
    }

    private static byte[] readResource(String resourcePath) throws IOException {
        try (InputStream in = DemoConsoleServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing console resource: " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    private final class SseClient implements AutoCloseable {
        private final HttpExchange exchange;
        private final OutputStream output;

        private SseClient(HttpExchange exchange) throws IOException {
            this.exchange = exchange;
            this.output = exchange.getResponseBody();
        }

        private synchronized void send(long id, String eventName, String data) {
            try {
                output.write(("id: " + id + "\n").getBytes(StandardCharsets.UTF_8));
                output.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
                output.write(("data: " + data.replace("\n", "\\n") + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException ignored) {
                close();
            }
        }

        private synchronized void sendComment(String comment) {
            try {
                output.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException ignored) {
                close();
            }
        }

        @Override
        public synchronized void close() {
            sseClients.remove(this);
            try {
                output.close();
            } catch (IOException ignored) {
            } finally {
                exchange.close();
            }
        }
    }
}

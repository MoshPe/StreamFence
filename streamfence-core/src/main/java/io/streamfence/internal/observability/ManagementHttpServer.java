package io.streamfence.internal.observability;

import io.streamfence.ServerMetrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal JDK {@link HttpServer} that serves {@code GET /metrics} from a
 * {@link ServerMetrics#scrape()} call. Runs on a single-thread executor — the
 * Prometheus scrape interval (15s default) means concurrency is not a concern
 * and keeping it small avoids another tuning knob.
 *
 * <p>Not a WebSocket endpoint; lives on a separate port from the game plane
 * so operators can firewall it off from untrusted networks.
 */
public final class ManagementHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ManagementHttpServer.class);

    private final ServerMetrics metrics;
    private final String host;
    private final int port;
    private HttpServer httpServer;

    public ManagementHttpServer(ServerMetrics metrics, String host, int port) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/metrics", new MetricsHandler(metrics));
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "management-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        this.httpServer = server;
        log.info("event=management_http_started host={} port={}", host, boundPort());
    }

    /**
     * Returns the actual bound port (useful when the caller requested 0).
     * Returns -1 if the server has not been started.
     */
    public int boundPort() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    public void stop(int graceSeconds) {
        if (httpServer == null) {
            return;
        }
        httpServer.stop(graceSeconds);
        log.info("event=management_http_stopped");
        httpServer = null;
    }

    @Override
    public void close() {
        stop(0);
    }

    private record MetricsHandler(ServerMetrics metrics) implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                byte[] body = metrics.scrape().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(body);
                }
            }
        }
    }
}

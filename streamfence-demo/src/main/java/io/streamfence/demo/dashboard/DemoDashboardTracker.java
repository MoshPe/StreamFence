package io.streamfence.demo.dashboard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class DemoDashboardTracker implements AutoCloseable {

    private static final int HISTORY_LIMIT = 60;

    private final Supplier<URI> metricsBaseUriSupplier;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Object stateLock = new Object();
    private final ArrayDeque<DashboardHistoryPoint> history = new ArrayDeque<>();
    private DemoMetricsSnapshot previousMetrics;
    private DashboardSnapshot latestSnapshot = DashboardSnapshot.empty("throughput", false, null, List.of());
    private String lastError;

    public DemoDashboardTracker(Supplier<URI> metricsBaseUriSupplier) {
        this.metricsBaseUriSupplier = Objects.requireNonNull(metricsBaseUriSupplier, "metricsBaseUriSupplier");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wsserver-demo-dashboard");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::pollOnce, 0L, 1L, TimeUnit.SECONDS);
    }

    public void reset(String presetId) {
        synchronized (stateLock) {
            previousMetrics = null;
            lastError = null;
            history.clear();
            latestSnapshot = DashboardSnapshot.empty(presetId, false, null, List.of());
        }
    }

    public DashboardSnapshot snapshot(
            String presetId,
            boolean scenarioPaused,
            List<DashboardProcessSnapshot> processes) {
        synchronized (stateLock) {
            return latestSnapshot.withContext(
                    presetId,
                    scenarioPaused,
                    lastError,
                    List.copyOf(history),
                    processes);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void pollOnce() {
        try {
            HttpRequest request = HttpRequest.newBuilder(metricsEndpoint())
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                recordError("Metrics scrape returned HTTP " + response.statusCode());
                return;
            }
            Instant capturedAt = Instant.now();
            DemoMetricsSnapshot currentMetrics = PrometheusMetricsParser.parse(response.body(), capturedAt);
            DashboardSnapshot currentSnapshot;
            synchronized (stateLock) {
                currentSnapshot = DashboardSnapshot.from(latestSnapshot.presetId(), currentMetrics, previousMetrics);
                previousMetrics = currentMetrics;
                latestSnapshot = currentSnapshot;
                lastError = null;
                appendHistory(currentSnapshot);
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordError("Metrics scrape unavailable");
        } catch (RuntimeException exception) {
            recordError("Metrics scrape failed");
        }
    }

    private void appendHistory(DashboardSnapshot snapshot) {
        if (history.size() == HISTORY_LIMIT) {
            history.removeFirst();
        }
        history.addLast(new DashboardHistoryPoint(
                snapshot.lastUpdated(),
                snapshot.activeClients(),
                snapshot.messagesPerSecond(),
                snapshot.sendBytesPerSecond(),
                snapshot.receiveBytesPerSecond()));
    }

    private void recordError(String message) {
        synchronized (stateLock) {
            lastError = message;
        }
    }

    private URI metricsEndpoint() {
        URI baseUri = metricsBaseUriSupplier.get();
        String base = baseUri.toString();
        return URI.create(base.endsWith("/") ? base + "metrics" : base + "/metrics");
    }
}

package io.streamfence.demo.dashboard;

import java.time.Duration;
import java.util.List;

public record DashboardSnapshot(
        String presetId,
        long activeClients,
        long totalMessages,
        double messagesPerSecond,
        double sendBytesPerSecond,
        double receiveBytesPerSecond,
        long queueOverflow,
        long retryCount,
        long dropped,
        long coalesced,
        String lastUpdated,
        boolean scenarioPaused,
        String lastError,
        List<NamespaceSnapshot> namespaces,
        List<DashboardHistoryPoint> history,
        List<DashboardProcessSnapshot> processes
) {

    public DashboardSnapshot {
        namespaces = List.copyOf(namespaces);
        history = List.copyOf(history);
        processes = List.copyOf(processes);
    }

    public DashboardSnapshot(
            String presetId,
            long activeClients,
            long totalMessages,
            double messagesPerSecond,
            double sendBytesPerSecond,
            double receiveBytesPerSecond,
            List<NamespaceSnapshot> namespaces) {
        this(
                presetId,
                activeClients,
                totalMessages,
                messagesPerSecond,
                sendBytesPerSecond,
                receiveBytesPerSecond,
                0L,
                0L,
                0L,
                0L,
                null,
                false,
                null,
                namespaces,
                List.of(),
                List.of());
    }

    public static DashboardSnapshot from(String presetId, DemoMetricsSnapshot current, DemoMetricsSnapshot previous) {
        double seconds = elapsedSeconds(current, previous);
        long publishedDelta = delta(current.messagesPublished(), previous == null ? 0L : previous.messagesPublished());
        long sentBytesDelta = delta(current.bytesPublished(), previous == null ? 0L : previous.bytesPublished());
        long receivedBytesDelta = delta(current.bytesReceived(), previous == null ? 0L : previous.bytesReceived());
        return new DashboardSnapshot(
                presetId,
                current.activeClients(),
                current.messagesPublished() + current.messagesReceived(),
                publishedDelta / seconds,
                sentBytesDelta / seconds,
                receivedBytesDelta / seconds,
                current.queueOverflow(),
                current.retryCount(),
                current.dropped(),
                current.coalesced(),
                current.capturedAt() == null ? null : current.capturedAt().toString(),
                false,
                null,
                current.namespaces(),
                List.of(),
                List.of());
    }

    public static DashboardSnapshot empty(
            String presetId,
            boolean scenarioPaused,
            String lastError,
            List<DashboardProcessSnapshot> processes) {
        return new DashboardSnapshot(
                presetId,
                0L,
                0L,
                0.0d,
                0.0d,
                0.0d,
                0L,
                0L,
                0L,
                0L,
                null,
                scenarioPaused,
                lastError,
                List.of(),
                List.of(),
                processes);
    }

    public DashboardSnapshot withContext(
            String presetId,
            boolean scenarioPaused,
            String lastError,
            List<DashboardHistoryPoint> history,
            List<DashboardProcessSnapshot> processes) {
        return new DashboardSnapshot(
                presetId,
                activeClients,
                totalMessages,
                messagesPerSecond,
                sendBytesPerSecond,
                receiveBytesPerSecond,
                queueOverflow,
                retryCount,
                dropped,
                coalesced,
                lastUpdated,
                scenarioPaused,
                lastError,
                namespaces,
                history,
                processes);
    }

    private static long delta(long current, long previous) {
        return current >= previous ? current - previous : current;
    }

    private static double elapsedSeconds(DemoMetricsSnapshot current, DemoMetricsSnapshot previous) {
        if (previous == null || previous.capturedAt() == null || current.capturedAt() == null) {
            return 1.0d;
        }
        long millis = Duration.between(previous.capturedAt(), current.capturedAt()).toMillis();
        return millis > 0L ? millis / 1000.0d : 1.0d;
    }
}

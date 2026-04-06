package io.streamfence.demo.dashboard;

public record DashboardHistoryPoint(
        String capturedAt,
        long activeClients,
        double messagesPerSecond,
        double sendBytesPerSecond,
        double receiveBytesPerSecond
) {
}

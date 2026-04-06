package io.streamfence.demo.dashboard;

import java.time.Instant;
import java.util.List;

public record DemoMetricsSnapshot(
        Instant capturedAt,
        long activeClients,
        long messagesPublished,
        long bytesPublished,
        long messagesReceived,
        long bytesReceived,
        long queueOverflow,
        long retryCount,
        long dropped,
        long coalesced,
        List<NamespaceSnapshot> namespaces
) {

    public DemoMetricsSnapshot {
        namespaces = List.copyOf(namespaces);
    }
}

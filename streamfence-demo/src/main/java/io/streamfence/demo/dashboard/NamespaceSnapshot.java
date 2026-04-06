package io.streamfence.demo.dashboard;

public record NamespaceSnapshot(
        String namespace,
        long activeClients,
        long messagesPublished,
        long bytesPublished,
        long messagesReceived,
        long bytesReceived,
        long queueOverflow,
        long retryCount,
        long dropped,
        long coalesced
) {
}

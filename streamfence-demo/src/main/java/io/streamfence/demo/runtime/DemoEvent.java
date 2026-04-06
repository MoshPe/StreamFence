package io.streamfence.demo.runtime;

public record DemoEvent(
        String timestamp,
        String processType,
        String processName,
        String direction,
        String category,
        String namespace,
        String topic,
        String clientId,
        String sender,
        String receiver,
        String summary,
        String payloadSource,
        String payloadContentType,
        Long payloadDeclaredSizeBytes,
        String payloadPreview
) {
}

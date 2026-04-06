package io.streamfence.demo.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class DemoSocketClientTest {

    @Test
    void payloadPreviewIsTruncatedForLargeMessages() {
        String preview = DemoSocketClient.payloadPreview("x".repeat(8_000));

        assertThat(preview).hasSizeLessThan(300);
        assertThat(preview).startsWith("xxxxxxxx");
        assertThat(preview).endsWith("...");
    }

    @Test
    void inspectPayloadPreservesBulkMetadata() {
        JSONObject payload = DemoPayloadFactory.bulkBotPayload();

        DemoSocketClient.PayloadMetadata metadata = DemoSocketClient.inspectPayload(payload);

        assertThat(metadata.source()).isEqualTo("bulk-bot");
        assertThat(metadata.contentType()).isEqualTo("application/octet-stream");
        assertThat(metadata.declaredSizeBytes()).isEqualTo(512L * 1024L);
    }
}

package io.streamfence.demo.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.demo.runtime.DemoPayloadKind;
import io.streamfence.demo.runtime.DemoPersona;
import java.time.Duration;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class DemoPayloadFactoryTest {

    @Test
    void priceBotPayloadHasExpectedQuoteShape() {
        JSONObject payload = DemoPayloadFactory.priceBotPayload();

        assertThat(payload.optString("symbol")).isEqualTo("WSR");
        assertThat(payload.optDouble("price")).isPositive();
        assertThat(payload.optString("source")).isEqualTo("price-bot");
    }

    @Test
    void alertsBotPayloadHasReliableMessageShape() {
        JSONObject payload = DemoPayloadFactory.alertsBotPayload();

        assertThat(payload.optString("id")).startsWith("alert-");
        assertThat(payload.optString("severity")).isEqualTo("info");
        assertThat(payload.optString("message")).contains("alerts");
        assertThat(payload.optJSONObject("context")).isNotNull();
        assertThat(payload.optJSONObject("context").optString("source")).isEqualTo("alerts-bot");
    }

    @Test
    void bulkBotPayloadUsesFixedBinarySampleShape() {
        JSONObject payload = DemoPayloadFactory.bulkBotPayload();
        String serialized = payload.toString();

        assertThat(payload.optString("blob")).hasSizeGreaterThan(690_000);
        assertThat(payload.optInt("declaredSizeBytes")).isEqualTo(512 * 1024);
        assertThat(payload.optString("contentType")).isEqualTo("application/octet-stream");
        assertThat(payload.optString("source")).isEqualTo("bulk-bot");
        assertThat(serialized).contains("\"declaredSizeBytes\":524288");
        assertThat(serialized).contains("\"contentType\":\"application/octet-stream\"");
        assertThat(serialized).contains("\"source\":\"bulk-bot\"");
    }

    @Test
    void payloadForSupportsPressureBotPersona() {
        JSONObject payload = DemoPayloadFactory.payloadFor(new DemoPersona(
                "pressure-bot",
                "/reliable",
                List.of("alerts"),
                true,
                Duration.ofMillis(100),
                DemoPayloadKind.JSON,
                8,
                Duration.ofMillis(900)));

        assertThat(payload.optString("id")).startsWith("alert-");
        assertThat(payload.optString("source")).isEqualTo("pressure-bot");
    }
}

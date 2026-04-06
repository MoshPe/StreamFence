package io.streamfence.demo.client;

import io.streamfence.demo.runtime.DemoPayloadKind;
import io.streamfence.demo.runtime.DemoPersona;
import java.util.Base64;
import org.json.JSONObject;

public final class DemoPayloadFactory {

    private static final int BULK_PAYLOAD_BYTES = 512 * 1024;
    private static final String BULK_BLOB = Base64.getEncoder().encodeToString(new byte[BULK_PAYLOAD_BYTES]);

    private DemoPayloadFactory() {
    }

    public static JSONObject priceBotPayload() {
        try {
            return new JSONObject()
                    .put("symbol", "WSR")
                    .put("price", 42.75)
                    .put("currency", "USD")
                    .put("source", "price-bot");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build price-bot payload", exception);
        }
    }

    public static JSONObject alertsBotPayload() {
        return alertsPayload("alerts-bot");
    }

    public static JSONObject pressureBotPayload() {
        return alertsPayload("pressure-bot");
    }

    private static JSONObject alertsPayload(String source) {
        try {
            return new JSONObject()
                    .put("id", "alert-1")
                    .put("severity", "info")
                    .put("message", source + " heartbeat")
                    .put("source", source)
                    .put("context", new JSONObject()
                            .put("source", source)
                            .put("sequence", 1));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build reliable demo payload", exception);
        }
    }

    public static JSONObject bulkBotPayload() {
        try {
            return new JSONObject()
                    .put("declaredSizeBytes", BULK_PAYLOAD_BYTES)
                    .put("contentType", "application/octet-stream")
                    .put("source", "bulk-bot")
                    .put("blob", BULK_BLOB);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build bulk-bot payload", exception);
        }
    }

    public static JSONObject payloadFor(DemoPersona persona) {
        if (persona == null) {
            throw new IllegalArgumentException("persona is required");
        }
        if (persona.payloadKind() == DemoPayloadKind.BINARY) {
            return bulkBotPayload();
        }
        return switch (persona.name()) {
            case "price-bot" -> priceBotPayload();
            case "alerts-bot" -> alertsBotPayload();
            case "pressure-bot" -> pressureBotPayload();
            default -> throw new IllegalArgumentException("Unknown demo persona: " + persona.name());
        };
    }
}

package io.streamfence.demo.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record DemoControlCommand(
        String type,
        String namespace,
        String topic,
        String clientId,
        String persona,
        String presetId,
        JsonNode payload
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DemoControlCommand {
        if (payload != null && payload.isNull()) {
            payload = null;
        }
    }

    public static DemoControlCommand broadcastSample(String namespace, String topic, JsonNode payload) {
        return new DemoControlCommand("broadcastSample", namespace, topic, null, null, null, payload);
    }

    public static DemoControlCommand targetedSample(String namespace, String clientId, String topic, JsonNode payload) {
        return new DemoControlCommand("targetedSample", namespace, topic, clientId, null, null, payload);
    }

    public static DemoControlCommand pause() {
        return new DemoControlCommand("pause", null, null, null, null, null, null);
    }

    public static DemoControlCommand resume() {
        return new DemoControlCommand("resume", null, null, null, null, null, null);
    }

    public static DemoControlCommand shutdown() {
        return new DemoControlCommand("shutdown", null, null, null, null, null, null);
    }

    public static DemoControlCommand restartPersona(String persona) {
        return new DemoControlCommand("restartPersona", null, null, null, persona, null, null);
    }

    public static DemoControlCommand applyPreset(String presetId) {
        return new DemoControlCommand("applyPreset", null, null, null, null, presetId, null);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize demo control command", exception);
        }
    }

    public static DemoControlCommand fromJson(String json) {
        try {
            return MAPPER.readValue(json, DemoControlCommand.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to parse demo control command", exception);
        }
    }
}

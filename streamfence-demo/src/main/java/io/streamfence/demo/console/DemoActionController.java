package io.streamfence.demo.console;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class DemoActionController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BROADCAST_NAMESPACE = "/non-reliable";
    private static final String BROADCAST_TOPIC = "prices";

    private final Consumer<String> actionSink;
    private final ActionDelegate actionDelegate;

    public DemoActionController(Consumer<String> actionSink) {
        this(actionSink, ActionDelegate.noop());
    }

    public DemoActionController(Consumer<String> actionSink, ActionDelegate actionDelegate) {
        this.actionSink = Objects.requireNonNull(actionSink, "actionSink");
        this.actionDelegate = Objects.requireNonNull(actionDelegate, "actionDelegate");
    }

    public static DemoActionController noop() {
        return new DemoActionController(action -> {}, ActionDelegate.noop());
    }

    public Response handle(String actionName, String requestBody) {
        Objects.requireNonNull(actionName, "actionName");
        try {
            return switch (actionName) {
                case "broadcast-sample" -> handleBroadcastSample();
                case "targeted-sample" -> handleTargetedSample(requestBody);
                case "restart-persona" -> handleRestartPersona(requestBody);
                case "pause-scenario" -> handlePauseScenario();
                case "resume-scenario" -> handleResumeScenario();
                case "apply-preset" -> handleApplyPreset(requestBody);
                default -> new Response(404, json(Map.of(
                        "status", "unknown-action",
                        "action", actionName,
                        "message", "Unknown action: " + actionName)));
            };
        } catch (IllegalArgumentException exception) {
            return new Response(400, json(Map.of(
                    "status", "invalid-request",
                    "action", actionName,
                    "message", exception.getMessage())));
        }
    }

    private Response handleBroadcastSample() {
        actionSink.accept("broadcast-sample");
        Map<String, Object> payload = fixedSamplePayload();
        actionDelegate.broadcastSample(BROADCAST_NAMESPACE, BROADCAST_TOPIC, payload);
        return accepted("broadcast-sample", "Broadcast sample queued");
    }

    private Response handleTargetedSample(String requestBody) {
        JsonNode body = readBody(requestBody);
        String clientId = requireText(body, "clientId", "targetClientId");
        actionSink.accept("targeted-sample");
        actionDelegate.targetedSample(BROADCAST_NAMESPACE, clientId, BROADCAST_TOPIC, fixedSamplePayload());
        return accepted("targeted-sample", "Targeted sample queued for " + clientId);
    }

    private Response handleRestartPersona(String requestBody) {
        JsonNode body = readBody(requestBody);
        String persona = requireText(body, "persona", "name");
        actionSink.accept("restart-persona");
        actionDelegate.restartPersona(persona);
        return accepted("restart-persona", "Restart requested for " + persona);
    }

    private Response handlePauseScenario() {
        actionSink.accept("pause-scenario");
        actionDelegate.pauseScenario();
        return accepted("pause-scenario", "Scenario paused");
    }

    private Response handleResumeScenario() {
        actionSink.accept("resume-scenario");
        actionDelegate.resumeScenario();
        return accepted("resume-scenario", "Scenario resumed");
    }

    private Response handleApplyPreset(String requestBody) {
        JsonNode body = readBody(requestBody);
        String presetId = requireText(body, "presetId", "preset");
        actionSink.accept("apply-preset");
        actionDelegate.applyPreset(presetId);
        return accepted("apply-preset", "Preset switch requested for " + presetId);
    }

    private static Response accepted(String actionName, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("action", actionName);
        body.put("message", message);
        return new Response(202, json(body));
    }

    private static Map<String, Object> fixedSamplePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", "WSR");
        payload.put("price", 42.75d);
        payload.put("source", "demo-console");
        return payload;
    }

    private static JsonNode readBody(String requestBody) {
        String payload = requestBody == null || requestBody.isBlank() ? "{}" : requestBody;
        try {
            return MAPPER.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON request body", exception);
        }
    }

    private static String requireText(JsonNode body, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = body.get(fieldName);
            if (node != null && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        throw new IllegalArgumentException("Missing required field: " + String.join(" or ", fieldNames));
    }

    private static String json(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize action response", exception);
        }
    }

    public interface ActionDelegate {

        default void broadcastSample(String namespace, String topic, Map<String, Object> payload) {
        }

        default void targetedSample(String namespace, String clientId, String topic, Map<String, Object> payload) {
        }

        default void restartPersona(String persona) {
        }

        default void pauseScenario() {
        }

        default void resumeScenario() {
        }

        default void applyPreset(String presetId) {
        }

        static ActionDelegate noop() {
            return new ActionDelegate() {
            };
        }
    }

    public record Response(int statusCode, String body) {
    }
}

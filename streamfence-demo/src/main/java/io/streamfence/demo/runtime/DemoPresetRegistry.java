package io.streamfence.demo.runtime;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DemoPresetRegistry {

    private static final String DEFAULT_PRESET_ID = "throughput";
    private static final Map<String, DemoPresetDefinition> PRESETS = buildPresets();

    private DemoPresetRegistry() {
    }

    public static String defaultPresetId() {
        return DEFAULT_PRESET_ID;
    }

    public static List<DemoPresetDefinition> supportedPresets() {
        return List.copyOf(PRESETS.values());
    }

    public static DemoPresetDefinition require(String presetId) {
        DemoPresetDefinition definition = PRESETS.get(presetId == null || presetId.isBlank() ? DEFAULT_PRESET_ID : presetId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown demo preset: " + presetId);
        }
        return definition;
    }

    private static Map<String, DemoPresetDefinition> buildPresets() {
        Map<String, DemoPresetDefinition> presets = new LinkedHashMap<>();
        presets.put("throughput", new DemoPresetDefinition(
                "throughput",
                "Throughput",
                "High-volume best-effort traffic focused on /non-reliable.",
                "demo/presets/throughput.yaml",
                List.of(new DemoPersona(
                        "price-bot",
                        "/non-reliable",
                        List.of("prices"),
                        false,
                        Duration.ofMillis(100),
                        DemoPayloadKind.JSON,
                        24,
                        Duration.ZERO))));
        presets.put("realtime", new DemoPresetDefinition(
                "realtime",
                "Realtime Feed",
                "Low-latency websocket-only live updates focused on freshness over backlog.",
                "demo/presets/realtime.yaml",
                List.of(new DemoPersona(
                        "price-bot",
                        "/non-reliable",
                        List.of("prices"),
                        false,
                        Duration.ofMillis(50),
                        DemoPayloadKind.JSON,
                        32,
                        Duration.ZERO))));
        presets.put("reliable", new DemoPresetDefinition(
                "reliable",
                "Reliable Delivery",
                "Authenticated AT_LEAST_ONCE traffic with pipelined in-flight messages.",
                "demo/presets/reliable.yaml",
                List.of(new DemoPersona(
                        "alerts-bot",
                        "/reliable",
                        List.of("alerts"),
                        true,
                        Duration.ofMillis(150),
                        DemoPayloadKind.JSON,
                        16,
                        Duration.ZERO))));
        presets.put("bulk", new DemoPresetDefinition(
                "bulk",
                "Bulk Transfer",
                "Large payload flow over the /bulk namespace.",
                "demo/presets/bulk.yaml",
                List.of(new DemoPersona(
                        "bulk-bot",
                        "/bulk",
                        List.of("binary.blob"),
                        true,
                        Duration.ofMillis(500),
                        DemoPayloadKind.BINARY,
                        4,
                        Duration.ZERO))));
        presets.put("pressure", new DemoPresetDefinition(
                "pressure",
                "Pressure Test",
                "Tight reliable queues with delayed acknowledgements to surface retries and overflow.",
                "demo/presets/pressure.yaml",
                List.of(new DemoPersona(
                        "pressure-bot",
                        "/reliable",
                        List.of("alerts"),
                        true,
                        Duration.ofMillis(100),
                        DemoPayloadKind.JSON,
                        8,
                        Duration.ofMillis(900)))));
        return Collections.unmodifiableMap(new LinkedHashMap<>(presets));
    }
}

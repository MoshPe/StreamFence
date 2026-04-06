package io.streamfence.demo.runtime;

import java.util.List;

public record DemoScenarioDefinition(List<DemoPersona> personas) {

    public DemoScenarioDefinition {
        personas = List.copyOf(personas);
    }

    public static DemoScenarioDefinition defaultScenario() {
        return forPreset(DemoPresetRegistry.defaultPresetId());
    }

    public static DemoScenarioDefinition forPreset(String presetId) {
        return DemoPresetRegistry.require(presetId).scenario();
    }
}

package io.streamfence.demo.runtime;

import java.util.List;

public record DemoPresetDefinition(
        String id,
        String label,
        String description,
        String configResource,
        List<DemoPersona> personas
) {

    public DemoPresetDefinition {
        personas = List.copyOf(personas);
    }

    public DemoScenarioDefinition scenario() {
        return new DemoScenarioDefinition(personas);
    }
}

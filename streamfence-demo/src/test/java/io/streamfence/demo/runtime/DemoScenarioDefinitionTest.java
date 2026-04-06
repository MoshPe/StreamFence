package io.streamfence.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DemoScenarioDefinitionTest {

    @Test
    void defaultScenarioMatchesTheThroughputPresetSwarm() {
        DemoScenarioDefinition scenario = DemoScenarioDefinition.defaultScenario();

        assertThat(scenario.personas()).satisfiesExactly(
                persona -> {
                    assertThat(persona.name()).isEqualTo("price-bot");
                    assertThat(persona.namespace()).isEqualTo("/non-reliable");
                    assertThat(persona.topics()).containsExactly("prices");
                    assertThat(persona.tokenRequired()).isFalse();
                    assertThat(persona.publishCadence()).isEqualTo(Duration.ofMillis(100));
                    assertThat(persona.payloadKind()).isEqualTo(DemoPayloadKind.JSON);
                    assertThat(persona.connectionCount()).isEqualTo(24);
                    assertThat(persona.ackDelay()).isEqualTo(Duration.ZERO);
                });
    }
}

package io.streamfence.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SimulationHarnessTest {

    @Test
    void simulationProducesMixedQueueOutcomes() {
        LoadSimulationHarness harness = new LoadSimulationHarness();

        LoadSimulationHarness.SimulationResult result = harness.run(8, 20);

        assertThat(result.accepted()).isGreaterThan(0);
        assertThat(result.rejected() + result.dropped() + result.coalesced()).isGreaterThan(0);
    }
}

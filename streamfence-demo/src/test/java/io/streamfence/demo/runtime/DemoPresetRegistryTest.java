package io.streamfence.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.SocketIoServerSpec;
import org.junit.jupiter.api.Test;

class DemoPresetRegistryTest {

    @Test
    void exposesTheSupportedPresetIdsAndSwarmSizes() {
        assertThat(DemoPresetRegistry.supportedPresets())
                .extracting(DemoPresetDefinition::id)
                .containsExactly("throughput", "realtime", "reliable", "bulk", "pressure");

        assertThat(DemoPresetRegistry.require("throughput").personas())
                .singleElement()
                .satisfies(persona -> {
                    assertThat(persona.namespace()).isEqualTo("/non-reliable");
                    assertThat(persona.connectionCount()).isEqualTo(24);
                    assertThat(persona.publishCadence()).hasMillis(100);
                });
        assertThat(DemoPresetRegistry.require("reliable").personas())
                .singleElement()
                .satisfies(persona -> {
                    assertThat(persona.namespace()).isEqualTo("/reliable");
                    assertThat(persona.connectionCount()).isEqualTo(16);
                    assertThat(persona.tokenRequired()).isTrue();
                    assertThat(persona.publishCadence()).hasMillis(150);
                });
        assertThat(DemoPresetRegistry.require("bulk").personas())
                .singleElement()
                .satisfies(persona -> {
                    assertThat(persona.namespace()).isEqualTo("/bulk");
                    assertThat(persona.connectionCount()).isEqualTo(4);
                    assertThat(persona.publishCadence()).hasMillis(500);
                    assertThat(persona.payloadKind()).isEqualTo(DemoPayloadKind.BINARY);
                });
        assertThat(DemoPresetRegistry.require("realtime").personas())
                .singleElement()
                .satisfies(persona -> {
                    assertThat(persona.name()).isEqualTo("price-bot");
                    assertThat(persona.namespace()).isEqualTo("/non-reliable");
                    assertThat(persona.connectionCount()).isEqualTo(32);
                    assertThat(persona.publishCadence()).hasMillis(50);
                    assertThat(persona.tokenRequired()).isFalse();
                });
        assertThat(DemoPresetRegistry.require("pressure").personas())
                .singleElement()
                .satisfies(persona -> {
                    assertThat(persona.namespace()).isEqualTo("/reliable");
                    assertThat(persona.connectionCount()).isEqualTo(8);
                    assertThat(persona.publishCadence()).hasMillis(100);
                    assertThat(persona.ackDelay()).hasMillis(900);
                });
    }

    @Test
    void bundledPresetResourcesLoadAsValidServerSpecs() {
        DemoPresetDefinition bulk = DemoPresetRegistry.require("bulk");
        SocketIoServerSpec spec = SocketIoServerSpec.fromClasspath(bulk.configResource());

        assertThat(spec.managementPort()).isEqualTo(9093);
        assertThat(spec.staticTokens()).containsEntry("demo-client", "change-me");
        assertThat(spec.namespaces())
                .filteredOn(namespace -> namespace.path().equals("/bulk"))
                .singleElement()
                .satisfies(namespace -> {
                    assertThat(namespace.topics()).containsExactly("binary.blob");
                    assertThat(namespace.allowPolling()).isFalse();
                });

        DemoPresetDefinition realtime = DemoPresetRegistry.require("realtime");
        SocketIoServerSpec realtimeSpec = SocketIoServerSpec.fromClasspath(realtime.configResource());
        assertThat(realtimeSpec.namespaces())
                .filteredOn(namespace -> namespace.path().equals("/non-reliable"))
                .singleElement()
                .satisfies(namespace -> {
                    assertThat(namespace.topics()).containsExactly("prices");
                    assertThat(namespace.allowPolling()).isFalse();
                    assertThat(namespace.coalesce()).isTrue();
                    assertThat(namespace.overflowAction()).isEqualTo(io.streamfence.OverflowAction.COALESCE);
                    assertThat(namespace.maxQueuedMessagesPerClient()).isGreaterThanOrEqualTo(64);
                });
    }
}

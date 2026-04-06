package io.streamfence.demo.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DemoActionControllerTest {

    @Test
    void broadcastSampleDelegatesWithFixedNamespaceTopicAndPayload() {
        AtomicReference<String> launcherEvent = new AtomicReference<>();
        AtomicReference<BroadcastInvocation> invocation = new AtomicReference<>();

        DemoActionController controller = new DemoActionController(
                launcherEvent::set,
                new DemoActionController.ActionDelegate() {
                    @Override
                    public void broadcastSample(String namespace, String topic, Map<String, Object> payload) {
                        invocation.set(new BroadcastInvocation(namespace, topic, payload));
                    }
                });

        DemoActionController.Response response = controller.handle("broadcast-sample", null);

        assertThat(launcherEvent).hasValue("broadcast-sample");
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("broadcast-sample");
        assertThat(invocation).hasValueSatisfying(val -> {
            assertThat(val.namespace()).isEqualTo("/non-reliable");
            assertThat(val.topic()).isEqualTo("prices");
            assertThat(val.payload()).containsEntry("source", "demo-console");
            assertThat(val.payload()).containsEntry("symbol", "WSR");
            assertThat(val.payload()).containsEntry("price", 42.75d);
        });
    }

    @Test
    void applyPresetDelegatesTheRequestedPresetId() {
        AtomicReference<String> presetId = new AtomicReference<>();
        DemoActionController controller = new DemoActionController(
                action -> {
                },
                new DemoActionController.ActionDelegate() {
                    @Override
                    public void applyPreset(String preset) {
                        presetId.set(preset);
                    }
                });

        DemoActionController.Response response = controller.handle("apply-preset", "{\"presetId\":\"bulk\"}");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("apply-preset");
        assertThat(presetId).hasValue("bulk");
    }

    private record BroadcastInvocation(String namespace, String topic, Map<String, Object> payload) {
        private BroadcastInvocation {
            payload = new LinkedHashMap<>(payload);
        }
    }
}

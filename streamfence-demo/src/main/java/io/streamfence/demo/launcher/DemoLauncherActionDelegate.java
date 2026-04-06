package io.streamfence.demo.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.streamfence.demo.console.DemoActionController;
import io.streamfence.demo.runtime.DemoControlCommand;
import java.util.Map;
import java.util.Objects;

public final class DemoLauncherActionDelegate implements DemoActionController.ActionDelegate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessControl processControl;

    public DemoLauncherActionDelegate(ProcessControl processControl) {
        this.processControl = Objects.requireNonNull(processControl, "processControl");
    }

    @Override
    public void broadcastSample(String namespace, String topic, Map<String, Object> payload) {
        processControl.sendServerCommand(DemoControlCommand.broadcastSample(
                namespace,
                topic,
                MAPPER.valueToTree(payload)));
    }

    @Override
    public void targetedSample(String namespace, String clientId, String topic, Map<String, Object> payload) {
        processControl.sendServerCommand(DemoControlCommand.targetedSample(
                namespace,
                clientId,
                topic,
                MAPPER.valueToTree(payload)));
    }

    @Override
    public void restartPersona(String persona) {
        processControl.restartPersona(persona);
    }

    @Override
    public void pauseScenario() {
        processControl.broadcastClientCommand(DemoControlCommand.pause());
    }

    @Override
    public void resumeScenario() {
        processControl.broadcastClientCommand(DemoControlCommand.resume());
    }

    @Override
    public void applyPreset(String presetId) {
        processControl.applyPreset(presetId);
    }

    interface ProcessControl {
        void sendServerCommand(DemoControlCommand command);

        void broadcastClientCommand(DemoControlCommand command);

        void restartPersona(String persona);

        default void applyPreset(String presetId) {
        }
    }
}

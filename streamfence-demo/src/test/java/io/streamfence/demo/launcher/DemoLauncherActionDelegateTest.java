package io.streamfence.demo.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.demo.runtime.DemoControlCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoLauncherActionDelegateTest {

    @Test
    void broadcastAndTargetedActionsBecomeServerCommands() {
        RecordingControl control = new RecordingControl();
        DemoLauncherActionDelegate delegate = new DemoLauncherActionDelegate(control);

        delegate.broadcastSample("/non-reliable", "prices", Map.of("symbol", "WSR"));
        delegate.targetedSample("/non-reliable", "client-7", "prices", Map.of("symbol", "WSR"));

        assertThat(control.serverCommands).hasSize(2);
        assertThat(control.serverCommands.getFirst().type()).isEqualTo("broadcastSample");
        assertThat(control.serverCommands.get(1).type()).isEqualTo("targetedSample");
        assertThat(control.serverCommands.get(1).clientId()).isEqualTo("client-7");
    }

    @Test
    void pauseResumeAndRestartPersonaDelegateToProcessControl() {
        RecordingControl control = new RecordingControl();
        DemoLauncherActionDelegate delegate = new DemoLauncherActionDelegate(control);

        delegate.pauseScenario();
        delegate.resumeScenario();
        delegate.restartPersona("price-bot");

        assertThat(control.clientCommands).extracting(DemoControlCommand::type)
                .containsExactly("pause", "resume");
        assertThat(control.restartedPersonas).containsExactly("price-bot");
    }

    private static final class RecordingControl implements DemoLauncherActionDelegate.ProcessControl {
        private final List<DemoControlCommand> serverCommands = new ArrayList<>();
        private final List<DemoControlCommand> clientCommands = new ArrayList<>();
        private final List<String> restartedPersonas = new ArrayList<>();

        @Override
        public void sendServerCommand(DemoControlCommand command) {
            serverCommands.add(command);
        }

        @Override
        public void broadcastClientCommand(DemoControlCommand command) {
            clientCommands.add(command);
        }

        @Override
        public void restartPersona(String persona) {
            restartedPersonas.add(persona);
        }
    }
}

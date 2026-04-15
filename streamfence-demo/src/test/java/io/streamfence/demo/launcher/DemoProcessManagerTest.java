package io.streamfence.demo.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoControlCommand;
import io.streamfence.demo.runtime.DemoEventWriter;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DemoProcessManagerTest {

    @Test
    void readinessIsTrackedFromSyntheticChildEvents() {
        DemoProcessManager manager = new DemoProcessManager(List.of("price-bot", "alerts-bot"));

        assertThat(manager.serverReady()).isFalse();
        assertThat(manager.clientReady("price-bot")).isFalse();

        manager.onChildLine("server", DemoEventWriter.toJsonLine(new DemoEvent(
                "2026-04-06T00:00:00Z",
                "server",
                "demo-server",
                "event",
                "server_ready",
                null,
                null,
                null,
                null,
                "demo-server",
                "ready",
                null,
                null,
                null,
                null)));

        manager.onChildLine("price-bot", DemoEventWriter.toJsonLine(new DemoEvent(
                "2026-04-06T00:00:01Z",
                "client",
                "price-bot",
                "event",
                "client_ready",
                "/non-reliable",
                null,
                null,
                null,
                "price-bot",
                "ready",
                null,
                null,
                null,
                null)));

        assertThat(manager.serverReady()).isTrue();
        assertThat(manager.clientReady("price-bot")).isTrue();
        assertThat(manager.clientReady("alerts-bot")).isFalse();
        assertThat(manager.recentHistory()).hasSize(2);
    }

    @Test
    void historyIsBoundedToTheMostRecentEvents() {
        DemoProcessManager manager = new DemoProcessManager(List.of());

        for (int index = 0; index < 80; index++) {
            manager.onChildLine("server", DemoEventWriter.toJsonLine(new DemoEvent(
                    "2026-04-06T00:00:" + String.format("%02d", index % 60) + "Z",
                    "server",
                    "demo-server",
                    "event",
                    "server_started",
                    null,
                    null,
                    null,
                    null,
                    "demo-server",
                    "event-" + index,
                    null,
                    null,
                    null,
                    null)));
        }

        assertThat(manager.recentHistory()).hasSizeLessThanOrEqualTo(64);
        assertThat(manager.recentHistory().get(manager.recentHistory().size() - 1).summary())
                .isEqualTo("event-79");
    }

    @Test
    void nonJsonLinesArePreservedAsLogEvents() {
        DemoProcessManager manager = new DemoProcessManager(List.of());

        manager.onChildLine("server", "[INFO] demo server started");

        assertThat(manager.recentHistory()).hasSize(1);
        DemoEvent event = manager.recentHistory().get(0);
        assertThat(event.category()).isEqualTo("log_line");
        assertThat(event.processName()).isEqualTo("server");
        assertThat(event.summary()).isEqualTo("[INFO] demo server started");
    }

    @Test
    void customServerUriIsAccepted() {
        assertThat(DemoLauncherMain.resolveServerUri(new String[]{"http://127.0.0.1:9999"}))
                .isEqualTo(URI.create("http://127.0.0.1:9999"));
    }

    @Test
    void defaultServerUriIsAccepted() {
        assertThat(DemoLauncherMain.resolveServerUri(new String[]{URI.create("http://127.0.0.1:9092").toString()}))
                .isEqualTo(URI.create("http://127.0.0.1:9092"));
    }

    @Test
    void launcherOptionsRejectPortsOutsideTcpRange() {
        assertThatThrownBy(() -> DemoLauncherOptions.fromArgs(new String[]{"--server-port=70000"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serverPort");

        assertThatThrownBy(() -> DemoLauncherOptions.fromArgs(new String[]{"--console-port=70000"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consolePort");
    }

    @Test
    void awaitServerReadyFailsPromptlyWhenChildDiesSilently() throws Exception {
        DemoProcessManager manager = new DemoProcessManager(List.of());
        DemoChildProcess child = DemoChildProcess.start(
                "server",
                DemoTestShell.silentShortLived(),
                line -> {
                });
        injectChild(manager, "server", child);

        long startedAt = System.nanoTime();
        try {
            assertThatThrownBy(() -> manager.awaitServerReady(5, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Demo child exited before readiness");
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertThat(elapsedMillis).isLessThan(2_000L);
        } finally {
            child.close();
        }
    }

    @Test
    void serverCommandsAreForwardedToTheServerChild() throws Exception {
        DemoProcessManager manager = new DemoProcessManager(List.of());
        RecordingChildHandle server = new RecordingChildHandle();
        injectChild(manager, "server", server);

        manager.sendServerCommand(DemoControlCommand.pause());

        assertThat(server.commands).singleElement().satisfies(command ->
                assertThat(command.type()).isEqualTo("pause"));
    }

    @Test
    void broadcastClientCommandTargetsOnlyClientChildren() throws Exception {
        DemoProcessManager manager = new DemoProcessManager(List.of("price-bot", "alerts-bot"));
        RecordingChildHandle server = new RecordingChildHandle();
        RecordingChildHandle price = new RecordingChildHandle();
        RecordingChildHandle alerts = new RecordingChildHandle();
        injectChild(manager, "server", server);
        injectChild(manager, "price-bot", price);
        injectChild(manager, "alerts-bot", alerts);

        manager.broadcastClientCommand(DemoControlCommand.resume());

        assertThat(server.commands).isEmpty();
        assertThat(price.commands).singleElement().satisfies(command ->
                assertThat(command.type()).isEqualTo("resume"));
        assertThat(alerts.commands).singleElement().satisfies(command ->
                assertThat(command.type()).isEqualTo("resume"));
    }

    @SuppressWarnings("unchecked")
    private static void injectChild(DemoProcessManager manager, String processName, DemoChildHandle child) throws Exception {
        Field field = DemoProcessManager.class.getDeclaredField("childProcesses");
        field.setAccessible(true);
        ((Map<String, DemoChildHandle>) field.get(manager)).put(processName, child);
    }

    private static final class RecordingChildHandle implements DemoChildHandle {
        private final List<DemoControlCommand> commands = new ArrayList<>();

        @Override
        public String processName() {
            return "recording";
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public boolean hasExited() {
            return false;
        }

        @Override
        public java.util.OptionalInt exitCode() {
            return java.util.OptionalInt.empty();
        }

        @Override
        public void sendCommand(DemoControlCommand command) {
            commands.add(command);
        }

        @Override
        public void close() {
        }
    }
}

package io.streamfence.demo.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoPersona;
import io.streamfence.demo.runtime.DemoScenarioDefinition;
import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DemoLauncherRuntimeTest {

    @Test
    void startupFailureClosesConsoleAndProcessManager() {
        AtomicBoolean consoleClosed = new AtomicBoolean();
        AtomicBoolean managerClosed = new AtomicBoolean();
        AtomicBoolean consoleStarted = new AtomicBoolean();

        DemoLauncherRuntime.Services services = new DemoLauncherRuntime.Services() {
            @Override
            public DemoLauncherRuntime.ConsoleHandle createConsole(
                    DemoLauncherOptions options,
                    Supplier<DemoScenarioDefinition> scenarioSupplier,
                    Consumer<DemoEvent> eventSink) {
                return new DemoLauncherRuntime.ConsoleHandle() {
                    @Override
                    public void start() {
                        consoleStarted.set(true);
                    }

                    @Override
                    public java.net.URI baseUri() {
                        return java.net.URI.create("http://127.0.0.1:9094");
                    }

                    @Override
                    public void publish(DemoEvent event) {
                    }

                    @Override
                    public void close() {
                        consoleClosed.set(true);
                    }
                };
            }

            @Override
            public DemoLauncherRuntime.ProcessManagerHandle createProcessManager(
                    List<String> expectedClientNames,
                    Consumer<DemoEvent> eventSink) {
                return new DemoLauncherRuntime.ProcessManagerHandle() {
                    @Override
                    public void startServerChild(int serverPort, int managementPort) {
                    }

                    @Override
                    public void startClientChild(DemoPersona persona, java.net.URI serverUri) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public boolean awaitServerReady(long timeoutMillis) {
                        return true;
                    }

                    @Override
                    public boolean awaitReady(long timeoutMillis) {
                        return false;
                    }

                    @Override
                    public List<DemoEvent> recentHistory() {
                        return List.of();
                    }

                    @Override
                    public void close() {
                        managerClosed.set(true);
                    }
                };
            }
        };

        assertThatThrownBy(() -> DemoLauncherRuntime.start(
                new DemoLauncherOptions(9092, 9093, 9094, Duration.ofSeconds(1)),
                new PrintStream(System.out),
                services))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(consoleStarted.get()).isTrue();
        assertThat(consoleClosed.get()).isTrue();
        assertThat(managerClosed.get()).isTrue();
    }

    @Test
    void applyPresetRestartsChildrenWithTheRequestedPreset() {
        List<String> serverStarts = new ArrayList<>();
        List<String> clientStarts = new ArrayList<>();
        AtomicBoolean stoppedClients = new AtomicBoolean();
        AtomicBoolean stoppedServer = new AtomicBoolean();

        DemoLauncherRuntime.Services services = new DemoLauncherRuntime.Services() {
            @Override
            public DemoLauncherRuntime.ConsoleHandle createConsole(
                    DemoLauncherOptions options,
                    Supplier<DemoScenarioDefinition> scenarioSupplier,
                    Consumer<DemoEvent> eventSink,
                    io.streamfence.demo.console.DemoActionController actionController,
                    java.util.function.Supplier<io.streamfence.demo.dashboard.DashboardSnapshot> dashboardSupplier) {
                return new DemoLauncherRuntime.ConsoleHandle() {
                    @Override
                    public void start() {
                    }

                    @Override
                    public URI baseUri() {
                        return URI.create("http://127.0.0.1:9094");
                    }

                    @Override
                    public void publish(DemoEvent event) {
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public DemoLauncherRuntime.ProcessManagerHandle createProcessManager(
                    List<String> expectedClientNames,
                    Consumer<DemoEvent> eventSink) {
                return new DemoLauncherRuntime.ProcessManagerHandle() {
                    @Override
                    public void startServerChild(int serverPort, int managementPort, String presetId) {
                        serverStarts.add(presetId);
                    }

                    @Override
                    public void startClientChild(DemoPersona persona, URI serverUri, String presetId) {
                        clientStarts.add(persona.name() + ":" + presetId);
                    }

                    @Override
                    public void stopServerChild() {
                        stoppedServer.set(true);
                    }

                    @Override
                    public void stopClientChildren() {
                        stoppedClients.set(true);
                    }

                    @Override
                    public void sendServerCommand(io.streamfence.demo.runtime.DemoControlCommand command) {
                    }

                    @Override
                    public void broadcastClientCommand(io.streamfence.demo.runtime.DemoControlCommand command) {
                    }

                    @Override
                    public void restartPersona(String persona) {
                    }

                    @Override
                    public boolean awaitServerReady(long timeoutMillis) {
                        return true;
                    }

                    @Override
                    public boolean awaitReady(long timeoutMillis) {
                        return true;
                    }

                    @Override
                    public List<DemoEvent> recentHistory() {
                        return List.of();
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };

        DemoLauncherRuntime runtime = DemoLauncherRuntime.start(
                new DemoLauncherOptions(9092, 9093, 9094, Duration.ofSeconds(1)),
                new PrintStream(System.out),
                services);
        try {
            runtime.applyPreset("bulk");

            assertThat(stoppedClients.get()).isTrue();
            assertThat(stoppedServer.get()).isTrue();
            assertThat(serverStarts).containsExactly("throughput", "bulk");
            assertThat(clientStarts).contains("price-bot:throughput");
            assertThat(clientStarts).contains("bulk-bot:bulk");
        } finally {
            runtime.close();
        }
    }
}

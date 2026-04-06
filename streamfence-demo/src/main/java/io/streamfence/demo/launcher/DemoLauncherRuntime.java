package io.streamfence.demo.launcher;

import io.streamfence.demo.console.DemoActionController;
import io.streamfence.demo.console.DemoConsoleServer;
import io.streamfence.demo.dashboard.DashboardSnapshot;
import io.streamfence.demo.dashboard.DemoDashboardTracker;
import io.streamfence.demo.runtime.DemoControlCommand;
import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoPersona;
import io.streamfence.demo.runtime.DemoPresetRegistry;
import io.streamfence.demo.runtime.DemoScenarioDefinition;
import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class DemoLauncherRuntime implements AutoCloseable, DemoLauncherActionDelegate.ProcessControl {

    private final DemoLauncherOptions options;
    private final ProcessManagerHandle processManager;
    private final AtomicReference<String> activePresetId;
    private final DemoDashboardTracker dashboardTracker;
    private final AtomicBoolean scenarioPaused = new AtomicBoolean();
    private ConsoleHandle consoleServer;

    private DemoLauncherRuntime(
            DemoLauncherOptions options,
            ProcessManagerHandle processManager,
            AtomicReference<String> activePresetId,
            DemoDashboardTracker dashboardTracker) {
        this.options = Objects.requireNonNull(options, "options");
        this.processManager = Objects.requireNonNull(processManager, "processManager");
        this.activePresetId = Objects.requireNonNull(activePresetId, "activePresetId");
        this.dashboardTracker = Objects.requireNonNull(dashboardTracker, "dashboardTracker");
    }

    public static DemoLauncherRuntime start(DemoLauncherOptions options, PrintStream out) {
        return start(options, out, Services.defaults());
    }

    static DemoLauncherRuntime start(DemoLauncherOptions options, PrintStream out, Services services) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(services, "services");

        AtomicReference<String> activePresetId = new AtomicReference<>(DemoPresetRegistry.defaultPresetId());
        Supplier<DemoScenarioDefinition> scenarioSupplier = () -> DemoScenarioDefinition.forPreset(activePresetId.get());
        AtomicReference<DemoLauncherRuntime> runtimeRef = new AtomicReference<>();
        DemoDashboardTracker dashboardTracker = new DemoDashboardTracker(
                () -> URI.create("http://127.0.0.1:" + options.managementPort()));
        ProcessManagerHandle processManager = null;
        ConsoleHandle consoleServer = null;
        boolean success = false;
        try {
            processManager = services.createProcessManager(
                    scenarioSupplier.get().personas().stream().map(DemoPersona::name).toList(),
                    event -> {
                        DemoLauncherRuntime runtime = runtimeRef.get();
                        if (runtime != null && runtime.consoleServer != null) {
                            runtime.consoleServer.publish(event);
                        }
                    });

            DemoLauncherRuntime runtime = new DemoLauncherRuntime(options, processManager, activePresetId, dashboardTracker);
            runtimeRef.set(runtime);

            DemoActionController actionController = new DemoActionController(action -> { }, new DemoLauncherActionDelegate(runtime));
            consoleServer = services.createConsole(
                    options,
                    scenarioSupplier,
                    event -> { },
                    actionController,
                    runtime::dashboardSnapshot);
            runtime.consoleServer = consoleServer;

            consoleServer.start();
            dashboardTracker.reset(activePresetId.get());
            dashboardTracker.start();
            out.println("launcher: console ready on " + consoleServer.baseUri());

            startPreset(runtime, scenarioSupplier.get().personas(), activePresetId.get());

            success = true;
            return runtime;
        } finally {
            if (!success) {
                safeClose(processManager);
                safeClose(dashboardTracker);
                safeClose(consoleServer);
            }
        }
    }

    public URI consoleBaseUri() {
        return consoleServer.baseUri();
    }

    public URI serverUri() {
        return options.serverUri();
    }

    public boolean awaitReady(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        return processManager.awaitReady(timeout.toMillis());
    }

    public List<DemoEvent> recentHistory() {
        return processManager.recentHistory();
    }

    @Override
    public void applyPreset(String presetId) {
        applyPresetInternal(presetId);
    }

    private void applyPresetInternal(String presetId) {
        String resolvedPresetId = DemoPresetRegistry.require(presetId).id();
        activePresetId.set(resolvedPresetId);
        dashboardTracker.reset(resolvedPresetId);
        scenarioPaused.set(false);
        processManager.stopClientChildren();
        processManager.stopServerChild();
        startPreset(this, DemoScenarioDefinition.forPreset(resolvedPresetId).personas(), resolvedPresetId);
    }

    public DashboardSnapshot dashboardSnapshot() {
        return dashboardTracker.snapshot(
                activePresetId.get(),
                scenarioPaused.get(),
                processManager.processStates());
    }

    @Override
    public void sendServerCommand(DemoControlCommand command) {
        processManager.sendServerCommand(command);
    }

    @Override
    public void broadcastClientCommand(DemoControlCommand command) {
        if ("pause".equals(command.type())) {
            scenarioPaused.set(true);
        } else if ("resume".equals(command.type())) {
            scenarioPaused.set(false);
        }
        processManager.broadcastClientCommand(command);
    }

    @Override
    public void restartPersona(String persona) {
        processManager.restartPersona(persona);
    }

    @Override
    public void close() {
        processManager.close();
        dashboardTracker.close();
        if (consoleServer != null) {
            consoleServer.close();
        }
    }

    private static void startPreset(DemoLauncherRuntime runtime, List<DemoPersona> personas, String presetId) {
        runtime.processManager.startServerChild(runtime.options.serverPort(), runtime.options.managementPort(), presetId);
        awaitServerReadiness(runtime.processManager, runtime.options, runtime.consoleServer);
        for (DemoPersona persona : personas) {
            runtime.processManager.startClientChild(persona, runtime.options.serverUri(), presetId);
        }
    }

    private static void awaitServerReadiness(
            ProcessManagerHandle processManager,
            DemoLauncherOptions options,
            ConsoleHandle consoleServer) {
        try {
            boolean ready = processManager.awaitServerReady(options.startupTimeout().toMillis());
            if (!ready) {
                throw new IllegalStateException("Timed out waiting for demo server readiness");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            consoleServer.close();
            processManager.close();
            throw new IllegalStateException("Interrupted while waiting for demo server readiness", exception);
        } catch (RuntimeException exception) {
            consoleServer.close();
            processManager.close();
            throw exception;
        }
    }

    private static void safeClose(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort rollback for partially started launcher resources.
        }
    }

    interface ConsoleHandle extends AutoCloseable {
        void start();

        URI baseUri();

        void publish(DemoEvent event);

        @Override
        void close();
    }

    interface ProcessManagerHandle extends AutoCloseable {
        default void startServerChild(int serverPort, int managementPort) {
            throw new AbstractMethodError("startServerChild(int, int) is not implemented");
        }

        default void startServerChild(int serverPort, int managementPort, String presetId) {
            startServerChild(serverPort, managementPort);
        }

        default void startClientChild(DemoPersona persona, URI serverUri) {
            throw new AbstractMethodError("startClientChild(DemoPersona, URI) is not implemented");
        }

        default void startClientChild(DemoPersona persona, URI serverUri, String presetId) {
            startClientChild(persona, serverUri);
        }

        default void stopServerChild() {
        }

        default void stopClientChildren() {
        }

        default void sendServerCommand(DemoControlCommand command) {
        }

        default void broadcastClientCommand(DemoControlCommand command) {
        }

        default void restartPersona(String persona) {
        }

        boolean awaitServerReady(long timeoutMillis) throws InterruptedException;

        boolean awaitReady(long timeoutMillis) throws InterruptedException;

        List<DemoEvent> recentHistory();

        default List<io.streamfence.demo.dashboard.DashboardProcessSnapshot> processStates() {
            return List.of();
        }

        @Override
        void close();
    }

    interface Services {
        default ConsoleHandle createConsole(
                DemoLauncherOptions options,
                Supplier<DemoScenarioDefinition> scenarioSupplier,
                Consumer<DemoEvent> eventSink) {
            throw new AbstractMethodError("createConsole(options, scenarioSupplier, eventSink) is not implemented");
        }

        default ConsoleHandle createConsole(
                DemoLauncherOptions options,
                Supplier<DemoScenarioDefinition> scenarioSupplier,
                Consumer<DemoEvent> eventSink,
                DemoActionController actionController,
                Supplier<DashboardSnapshot> dashboardSupplier) {
            return createConsole(options, scenarioSupplier, eventSink);
        }

        ProcessManagerHandle createProcessManager(
                List<String> expectedClientNames,
                Consumer<DemoEvent> eventSink);

        static Services defaults() {
            return new Services() {
                @Override
                public ConsoleHandle createConsole(
                        DemoLauncherOptions options,
                        Supplier<DemoScenarioDefinition> scenarioSupplier,
                        Consumer<DemoEvent> eventSink,
                        DemoActionController actionController,
                        Supplier<DashboardSnapshot> dashboardSupplier) {
                    DemoConsoleServer consoleServer = DemoConsoleServer.create(
                            options.consolePort(),
                            options::serverUri,
                            () -> URI.create("http://127.0.0.1:" + options.managementPort()),
                            () -> DemoPresetRegistry.require(dashboardSupplier.get().presetId()),
                            dashboardSupplier,
                            actionController);
                    return new ConsoleHandle() {
                        @Override
                        public void start() {
                            consoleServer.start();
                        }

                        @Override
                        public URI baseUri() {
                            return consoleServer.baseUri();
                        }

                        @Override
                        public void publish(DemoEvent event) {
                            consoleServer.publish(event);
                        }

                        @Override
                        public void close() {
                            consoleServer.close();
                        }
                    };
                }

                @Override
                public ProcessManagerHandle createProcessManager(
                        List<String> expectedClientNames,
                        Consumer<DemoEvent> eventSink) {
                    DemoProcessManager processManager = new DemoProcessManager(expectedClientNames, eventSink);
                    return new ProcessManagerHandle() {
                        @Override
                        public void startServerChild(int serverPort, int managementPort, String presetId) {
                            processManager.startServerChild(serverPort, managementPort, presetId);
                        }

                        @Override
                        public void startClientChild(DemoPersona persona, URI serverUri, String presetId) {
                            processManager.startClientChild(persona, serverUri, presetId);
                        }

                        @Override
                        public void stopServerChild() {
                            processManager.stopServerChild();
                        }

                        @Override
                        public void stopClientChildren() {
                            processManager.stopClientChildren();
                        }

                        @Override
                        public void sendServerCommand(DemoControlCommand command) {
                            processManager.sendServerCommand(command);
                        }

                        @Override
                        public void broadcastClientCommand(DemoControlCommand command) {
                            processManager.broadcastClientCommand(command);
                        }

                        @Override
                        public void restartPersona(String persona) {
                            processManager.restartPersona(persona);
                        }

                        @Override
                        public boolean awaitServerReady(long timeoutMillis) throws InterruptedException {
                            return processManager.awaitServerReady(timeoutMillis, TimeUnit.MILLISECONDS);
                        }

                        @Override
                        public boolean awaitReady(long timeoutMillis) throws InterruptedException {
                            return processManager.awaitReady(timeoutMillis, TimeUnit.MILLISECONDS);
                        }

                        @Override
                        public List<DemoEvent> recentHistory() {
                            return processManager.recentHistory();
                        }

                        @Override
                        public List<io.streamfence.demo.dashboard.DashboardProcessSnapshot> processStates() {
                            return processManager.processStates();
                        }

                        @Override
                        public void close() {
                            processManager.close();
                        }
                    };
                }
            };
        }
    }
}

package io.streamfence.demo.launcher;

import io.streamfence.demo.dashboard.DashboardProcessSnapshot;
import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoEventParser;
import io.streamfence.demo.runtime.DemoPersona;
import io.streamfence.demo.runtime.DemoControlCommand;
import io.streamfence.demo.runtime.DemoPresetRegistry;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public final class DemoProcessManager implements AutoCloseable, DemoLauncherActionDelegate.ProcessControl {

    private static final int HISTORY_LIMIT = 64;
    private static final long READINESS_POLL_MILLIS = 250L;

    private final List<String> expectedClientNames;
    private final Map<String, Boolean> clientReady = new ConcurrentHashMap<>();
    private final Map<String, DemoChildHandle> childProcesses = new ConcurrentHashMap<>();
    private final Map<String, DemoPersona> personasByName = new ConcurrentHashMap<>();
    private final ArrayDeque<DemoEvent> recentHistory = new ArrayDeque<>();
    private final Object historyLock = new Object();
    private final List<DemoChildHandle> children = new ArrayList<>();
    private final Consumer<DemoEvent> eventSink;
    private volatile boolean serverReady;
    private volatile URI activeServerUri;
    private volatile String activePresetId = DemoPresetRegistry.defaultPresetId();

    public DemoProcessManager(Collection<String> expectedClientNames) {
        this(expectedClientNames, event -> {
        });
    }

    public DemoProcessManager(Collection<String> expectedClientNames, Consumer<DemoEvent> eventSink) {
        this.expectedClientNames = List.copyOf(expectedClientNames);
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public void onChildLine(String processName, String line) {
        Objects.requireNonNull(processName, "processName");
        Objects.requireNonNull(line, "line");

        DemoEvent event = parseOrWrap(processName, line);
        synchronized (historyLock) {
            if (recentHistory.size() == HISTORY_LIMIT) {
                recentHistory.removeFirst();
            }
            recentHistory.addLast(event);
            historyLock.notifyAll();
        }
        if ("server_ready".equals(event.category())) {
            serverReady = true;
        }
        if ("client_ready".equals(event.category())) {
            clientReady.put(processName, true);
        }
        eventSink.accept(event);
    }

    public boolean serverReady() {
        return serverReady;
    }

    public boolean clientReady(String processName) {
        return clientReady.getOrDefault(processName, false);
    }

    public List<DemoEvent> recentHistory() {
        synchronized (historyLock) {
            return List.copyOf(recentHistory);
        }
    }

    public DemoChildProcess startServerChild() {
        return startServerChild(9092, 9093);
    }

    public DemoChildProcess startServerChild(int serverPort) {
        return startServerChild(serverPort, 9093);
    }

    public DemoChildProcess startServerChild(int serverPort, int managementPort) {
        return startServerChild(serverPort, managementPort, activePresetId);
    }

    public DemoChildProcess startServerChild(int serverPort, int managementPort, String presetId) {
        activePresetId = presetId == null || presetId.isBlank() ? DemoPresetRegistry.defaultPresetId() : presetId;
        DemoChildProcess child = startChild(
                "server",
                launcherJavaCommand(
                        "io.wsserver.demo.server.DemoServerMain",
                        "--server-port=" + serverPort,
                        "--management-port=" + managementPort,
                        "--preset=" + activePresetId));
        childProcesses.put("server", child);
        children.add(child);
        return child;
    }

    public DemoChildProcess startClientChild(DemoPersona persona, URI serverUri) {
        return startClientChild(persona, serverUri, activePresetId);
    }

    public DemoChildProcess startClientChild(DemoPersona persona, URI serverUri, String presetId) {
        Objects.requireNonNull(persona, "persona");
        Objects.requireNonNull(serverUri, "serverUri");
        personasByName.put(persona.name(), persona);
        activeServerUri = serverUri;
        activePresetId = presetId == null || presetId.isBlank() ? DemoPresetRegistry.defaultPresetId() : presetId;

        List<String> command = launcherJavaCommand(
                "io.wsserver.demo.client.DemoClientMain",
                persona.name(),
                serverUri.toString(),
                "--preset=" + activePresetId);
        DemoChildProcess child = startChild(persona.name(), command);
        childProcesses.put(persona.name(), child);
        children.add(child);
        return child;
    }

    public void startClients(List<DemoPersona> personas, URI serverUri) {
        for (DemoPersona persona : personas) {
            startClientChild(persona, serverUri);
        }
    }

    public void stopServerChild() {
        DemoChildHandle server = childProcesses.remove("server");
        serverReady = false;
        if (server != null) {
            server.close();
        }
    }

    public void stopClientChildren() {
        for (String processName : List.copyOf(childProcesses.keySet())) {
            if ("server".equals(processName)) {
                continue;
            }
            DemoChildHandle child = childProcesses.remove(processName);
            clientReady.put(processName, false);
            if (child != null) {
                child.close();
            }
        }
        personasByName.clear();
    }

    @Override
    public void sendServerCommand(DemoControlCommand command) {
        DemoChildHandle child = childProcesses.get("server");
        if (child == null) {
            throw new IllegalStateException("Server child is not running");
        }
        child.sendCommand(command);
    }

    @Override
    public void broadcastClientCommand(DemoControlCommand command) {
        for (Map.Entry<String, DemoChildHandle> entry : childProcesses.entrySet()) {
            if ("server".equals(entry.getKey())) {
                continue;
            }
            entry.getValue().sendCommand(command);
        }
    }

    @Override
    public void restartPersona(String persona) {
        DemoPersona definition = personasByName.get(persona);
        if (definition == null || activeServerUri == null) {
            throw new IllegalArgumentException("Unknown running persona: " + persona);
        }
        DemoChildHandle existing = childProcesses.remove(persona);
        clientReady.put(persona, false);
        if (existing != null) {
            existing.close();
        }
        DemoChildProcess child = startClientChild(definition, activeServerUri, activePresetId);
        childProcesses.put(persona, child);
    }

    public boolean awaitServerReady(long timeout, TimeUnit unit) throws InterruptedException {
        return awaitCondition("server", () -> serverReady, timeout, unit);
    }

    public boolean awaitClientReady(String processName, long timeout, TimeUnit unit) throws InterruptedException {
        return awaitCondition(processName, () -> clientReady(processName), timeout, unit);
    }

    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return awaitAllReady(timeout, unit);
    }

    public List<DashboardProcessSnapshot> processStates() {
        List<DashboardProcessSnapshot> states = new ArrayList<>();
        DemoChildHandle server = childProcesses.get("server");
        states.add(new DashboardProcessSnapshot(
                "server",
                "server",
                serverReady,
                server != null && server.isAlive()));
        for (DemoPersona persona : personasByName.values().stream()
                .sorted(java.util.Comparator.comparing(DemoPersona::name))
                .toList()) {
            DemoChildHandle child = childProcesses.get(persona.name());
            states.add(new DashboardProcessSnapshot(
                    persona.name(),
                    "client",
                    clientReady(persona.name()),
                    child != null && child.isAlive()));
        }
        return states;
    }

    @Override
    public void close() {
        for (int index = children.size() - 1; index >= 0; index--) {
            children.get(index).close();
        }
    }

    private DemoChildProcess startChild(String processName, List<String> command) {
        return DemoChildProcess.start(processName, command, line -> onChildLine(processName, line));
    }

    private boolean awaitAllReady(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        synchronized (historyLock) {
            while (!(serverReady && expectedClientNames.stream().allMatch(this::clientReady))) {
                if (!allStartedChildrenAlive()) {
                    throw new IllegalStateException("A demo child exited before all processes became ready");
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                long waitMillis = Math.max(1L, Math.min(
                        READINESS_POLL_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                historyLock.wait(waitMillis);
            }
            return serverReady && expectedClientNames.stream().allMatch(this::clientReady);
        }
    }

    private boolean awaitCondition(String processName, BooleanSupplier condition, long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        synchronized (historyLock) {
            while (!condition.getAsBoolean()) {
                if (!childIsAlive(processName)) {
                    throw new IllegalStateException("Demo child exited before readiness: " + processName);
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                long waitMillis = Math.max(1L, Math.min(
                        READINESS_POLL_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                historyLock.wait(waitMillis);
                if (!childIsAlive(processName) && !condition.getAsBoolean()) {
                    throw new IllegalStateException("Demo child exited before readiness: " + processName);
                }
            }
            return condition.getAsBoolean();
        }
    }

    private static List<String> launcherJavaCommand(String mainClass, String... args) {
        String javaBinary = javaBinary();
        List<String> command = new ArrayList<>();
        command.add(javaBinary);
        command.add("-cp");
        command.add(runtimeClasspath());
        command.add(mainClass);
        command.addAll(List.of(args));
        return command;
    }

    private static String runtimeClasspath() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        while (loader != null) {
            collectClasspathEntries(loader, entries);
            loader = loader.getParent();
        }
        if (entries.isEmpty()) {
            String classPath = System.getProperty("java.class.path", "");
            if (!classPath.isBlank()) {
                entries.addAll(List.of(classPath.split(File.pathSeparator)));
            }
        }
        return entries.stream().collect(Collectors.joining(File.pathSeparator));
    }

    private static void collectClasspathEntries(ClassLoader loader, LinkedHashSet<String> entries) {
        if (loader instanceof URLClassLoader urlClassLoader) {
            addUrls(entries, urlClassLoader.getURLs());
            return;
        }
        try {
            var method = loader.getClass().getMethod("getURLs");
            if (URL[].class.isAssignableFrom(method.getReturnType())) {
                Object result = method.invoke(loader);
                if (result instanceof URL[] urls) {
                    addUrls(entries, urls);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to java.class.path below.
        }
    }

    private static void addUrls(LinkedHashSet<String> entries, URL[] urls) {
        for (URL url : urls) {
            if (url == null) {
                continue;
            }
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                try {
                    entries.add(Path.of(url.toURI()).toString());
                } catch (Exception ignored) {
                    entries.add(url.getPath());
                }
            } else {
                entries.add(url.toString());
            }
        }
    }

    private DemoEvent parseOrWrap(String processName, String line) {
        try {
            return DemoEventParser.parse(line);
        } catch (IllegalArgumentException exception) {
            return new DemoEvent(
                    java.time.Instant.now().toString(),
                    "log",
                    processName,
                    "event",
                    "log_line",
                    null,
                    null,
                    null,
                    null,
                    processName,
                    line,
                    null,
                    null,
                    null,
                    line);
        }
    }

    private boolean childIsAlive(String processName) {
        DemoChildHandle child = childProcesses.get(processName);
        return child == null || child.isAlive();
    }

    private boolean allStartedChildrenAlive() {
        for (DemoChildHandle child : childProcesses.values()) {
            if (!child.isAlive()) {
                return false;
            }
        }
        return true;
    }

    private static String javaBinary() {
        String executable = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

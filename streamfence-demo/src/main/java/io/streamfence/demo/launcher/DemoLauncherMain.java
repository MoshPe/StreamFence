package io.streamfence.demo.launcher;

import io.streamfence.demo.runtime.DemoEventWriter;
import java.io.PrintStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public final class DemoLauncherMain {

    private static final URI DEFAULT_SERVER_URI = URI.create("http://127.0.0.1:9092");

    private DemoLauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        try (DemoLauncherHandle handle = start(DemoLauncherOptions.fromArgs(args), System.out)) {
            if (!handle.awaitReady()) {
                throw new IllegalStateException("Timed out waiting for demo launcher readiness");
            }
            System.out.println("launcher: all demo children ready");

            CountDownLatch shutdown = new CountDownLatch(1);
            Thread shutdownHook = new Thread(shutdown::countDown, "wsserver-demo-launcher-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                shutdown.await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM is already shutting down.
                }
            }
        }
    }

    public static DemoLauncherHandle start(DemoLauncherOptions options, PrintStream out) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        DemoLauncherRuntime runtime = DemoLauncherRuntime.start(options, out);
        return new DemoLauncherHandle(runtime, options.startupTimeout());
    }

    public static DemoLauncherHandle startForTest(String[] args) throws InterruptedException {
        return (DemoLauncherHandle) startForTest(args, DemoLauncherMain::start);
    }

    static TestHandle startForTest(String[] args, HandleStarter starter) throws InterruptedException {
        DemoLauncherOptions options = DemoLauncherOptions.fromArgs(args);
        TestHandle handle = starter.start(options, new PrintStream(OutputStream.nullOutputStream()));
        try {
            if (!handle.awaitReady()) {
                String history = handle.recentHistory().stream()
                        .map(DemoEventWriter::toJsonLine)
                        .collect(Collectors.joining(System.lineSeparator()));
                throw new IllegalStateException(
                        "Timed out waiting for demo launcher readiness. Recent history:\n" + history);
            }
            return handle;
        } catch (InterruptedException | RuntimeException exception) {
            handle.close();
            throw exception;
        }
    }

    static URI resolveServerUri(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return DEFAULT_SERVER_URI;
        }
        String firstArg = args[0];
        if (firstArg.startsWith("--")) {
            return DemoLauncherOptions.fromArgs(args).serverUri();
        }
        return URI.create(firstArg);
    }

    interface HandleStarter {
        TestHandle start(DemoLauncherOptions options, PrintStream out);
    }

    interface TestHandle extends AutoCloseable {
        URI consoleUri();

        URI serverUri();

        boolean awaitReady() throws InterruptedException;

        java.util.List<io.streamfence.demo.runtime.DemoEvent> recentHistory();

        @Override
        void close();
    }

    public static final class DemoLauncherHandle implements TestHandle {

        private final DemoLauncherRuntime runtime;
        private final java.time.Duration startupTimeout;

        private DemoLauncherHandle(DemoLauncherRuntime runtime, java.time.Duration startupTimeout) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        }

        @Override
        public URI consoleUri() {
            return runtime.consoleBaseUri();
        }

        @Override
        public URI serverUri() {
            return runtime.serverUri();
        }

        @Override
        public boolean awaitReady() throws InterruptedException {
            return runtime.awaitReady(startupTimeout);
        }

        @Override
        public java.util.List<io.streamfence.demo.runtime.DemoEvent> recentHistory() {
            return runtime.recentHistory();
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}

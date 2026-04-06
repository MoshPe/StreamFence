package io.streamfence.demo.launcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import io.streamfence.demo.runtime.DemoControlCommand;

public final class DemoChildProcess implements DemoChildHandle {

    private static final long SHUTDOWN_COMMAND_WAIT_MILLIS = 1_000L;
    private static final long DESTROY_WAIT_MILLIS = 750L;
    private static final long STDOUT_JOIN_WAIT_MILLIS = 500L;

    private final String processName;
    private final Process process;
    private final Thread stdoutThread;
    private final BufferedWriter stdinWriter;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DemoChildProcess(String processName, Process process, Thread stdoutThread, BufferedWriter stdinWriter) {
        this.processName = Objects.requireNonNull(processName, "processName");
        this.process = Objects.requireNonNull(process, "process");
        this.stdoutThread = Objects.requireNonNull(stdoutThread, "stdoutThread");
        this.stdinWriter = Objects.requireNonNull(stdinWriter, "stdinWriter");
    }

    public static DemoChildProcess start(
            String processName,
            List<String> command,
            Consumer<String> stdoutConsumer) {
        Objects.requireNonNull(processName, "processName");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(stdoutConsumer, "stdoutConsumer");

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            BufferedWriter stdinWriter = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread stdoutThread = new Thread(() -> drainStdout(process, stdoutConsumer),
                    "wsserver-demo-" + processName + "-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();
            return new DemoChildProcess(processName, process, stdoutThread, stdinWriter);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start demo child process " + processName, exception);
        }
    }

    public String processName() {
        return processName;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public boolean hasExited() {
        return !process.isAlive();
    }

    public java.util.OptionalInt exitCode() {
        try {
            return java.util.OptionalInt.of(process.exitValue());
        } catch (IllegalThreadStateException exception) {
            return java.util.OptionalInt.empty();
        }
    }

    public synchronized void sendCommand(DemoControlCommand command) {
        Objects.requireNonNull(command, "command");
        sendLine(command.toJson());
    }

    public synchronized void sendLine(String line) {
        Objects.requireNonNull(line, "line");
        if (closed.get()) {
            throw new IllegalStateException("Demo child process is already closed: " + processName);
        }
        try {
            stdinWriter.write(line);
            stdinWriter.newLine();
            stdinWriter.flush();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write command to demo child process " + processName, exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sendShutdownCommand();
        closeStdin();
        try {
            if (!process.waitFor(SHUTDOWN_COMMAND_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy();
            }
            if (!process.waitFor(DESTROY_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                destroyProcessTree();
                process.destroyForcibly();
                process.waitFor(DESTROY_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyProcessTree();
            process.destroyForcibly();
        } finally {
            closeStdin();
            stdoutThread.interrupt();
            try {
                stdoutThread.join(STDOUT_JOIN_WAIT_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendShutdownCommand() {
        try {
            stdinWriter.write(DemoControlCommand.shutdown().toJson());
            stdinWriter.newLine();
            stdinWriter.flush();
        } catch (IOException ignored) {
            // Child may already be gone or stdin may already be closed.
        }
    }

    private void closeStdin() {
        try {
            stdinWriter.close();
        } catch (IOException ignored) {
            // Best effort during shutdown.
        }
    }

    private void destroyProcessTree() {
        process.toHandle().descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (UnsupportedOperationException ignored) {
                // Best effort on platforms that do not support full process-handle control.
            }
        });
    }

    private static void drainStdout(Process process, Consumer<String> stdoutConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdoutConsumer.accept(line);
            }
        } catch (IOException ignored) {
            // Child process ended or stdout was interrupted during shutdown.
        }
    }
}

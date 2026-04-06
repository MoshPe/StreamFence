package io.streamfence.demo.server;

import io.streamfence.SocketIoServer;
import io.streamfence.demo.runtime.DemoControlCommand;
import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoEventWriter;
import io.streamfence.demo.runtime.DemoPresetRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public final class DemoServerMain {

    private static final int MAX_TCP_PORT = 65_535;

    private DemoServerMain() {
    }

    public static void main(String[] args) {
        run(System.out, resolveServerPort(args), resolveManagementPort(args), resolvePresetId(args));
    }

    static void run(PrintStream out, int port, int managementPort) {
        run(out, port, managementPort, DemoPresetRegistry.defaultPresetId());
    }

    static void run(PrintStream out, int port, int managementPort, String presetId) {
        Objects.requireNonNull(out, "out");
        CountDownLatch shutdown = new CountDownLatch(1);
        Thread shutdownHook = new Thread(shutdown::countDown, "wsserver-demo-server-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try (SocketIoServer server = SocketIoServer.builder()
                .fromClasspath(DemoPresetRegistry.require(presetId).configResource())
                .port(port)
                .managementPort(managementPort)
                .listener(new DemoServerListener(out))
                .buildServer()) {
            server.start();
            DemoEventWriter.write(out, new DemoEvent(
                    Instant.now().toString(),
                    "server",
                    "demo-server",
                    "event",
                    "server_ready",
                    null,
                    null,
                    null,
                    null,
                    "demo-server",
                    "demo server ready on " + server.spec().host() + ":" + server.spec().port(),
                    null,
                    null,
                    null,
                    null));
            startCommandLoop(server, shutdown);
            shutdown.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down.
            }
        }
    }

    private static int resolveServerPort(String[] args) {
        if (args == null || args.length == 0) {
            return 9092;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--server-port=")) {
                return validatePort(
                        "serverPort",
                        Integer.parseInt(arg.substring("--server-port=".length())),
                        false);
            }
        }
        return 9092;
    }

    private static int resolveManagementPort(String[] args) {
        if (args == null || args.length == 0) {
            return 9093;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--management-port=")) {
                return validatePort(
                        "managementPort",
                        Integer.parseInt(arg.substring("--management-port=".length())),
                        true);
            }
        }
        return 9093;
    }

    private static String resolvePresetId(String[] args) {
        if (args == null || args.length == 0) {
            return DemoPresetRegistry.defaultPresetId();
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--preset=")) {
                return DemoPresetRegistry.require(arg.substring("--preset=".length())).id();
            }
        }
        return DemoPresetRegistry.defaultPresetId();
    }

    private static void startCommandLoop(SocketIoServer server, CountDownLatch shutdown) {
        Thread controlThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    DemoControlCommand command = DemoControlCommand.fromJson(line);
                    switch (command.type()) {
                        case "broadcastSample" -> server.publish(command.namespace(), command.topic(), command.payload());
                        case "targetedSample" -> server.publishTo(
                                command.namespace(),
                                command.clientId(),
                                command.topic(),
                                command.payload());
                        case "shutdown" -> shutdown.countDown();
                        default -> {
                            // Ignore commands intended for other child types.
                        }
                    }
                }
            } catch (Exception ignored) {
                // Best effort: stdin may be closed when the launcher exits.
            } finally {
                shutdown.countDown();
            }
        }, "wsserver-demo-server-commands");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    private static int validatePort(String fieldName, int port, boolean allowZero) {
        int minimum = allowZero ? 0 : 1;
        if (port < minimum || port > MAX_TCP_PORT) {
            String range = allowZero ? "0.." + MAX_TCP_PORT : "1.." + MAX_TCP_PORT;
            throw new IllegalArgumentException(fieldName + " must be in range " + range);
        }
        return port;
    }
}

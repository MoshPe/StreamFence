package io.streamfence.demo.client;

import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoEventWriter;
import io.streamfence.demo.runtime.DemoControlCommand;
import io.streamfence.demo.runtime.DemoPersona;
import io.streamfence.demo.runtime.DemoPresetRegistry;
import io.streamfence.demo.runtime.DemoScenarioDefinition;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public final class DemoClientMain {

    private static final String DEFAULT_SERVER_URI = "http://127.0.0.1:9092";

    private DemoClientMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args, System.out);
    }

    static void run(String[] args, PrintStream out) throws Exception {
        Objects.requireNonNull(out, "out");
        String presetId = resolvePresetId(args);
        DemoPersona persona = resolvePersona(args, presetId);
        URI serverUri = URI.create(resolveServerUri(args));
        String token = persona.tokenRequired() ? "change-me" : null;

        CountDownLatch shutdown = new CountDownLatch(1);
        Thread shutdownHook = new Thread(shutdown::countDown, "wsserver-demo-client-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try (DemoClientSwarm swarm = new DemoClientSwarm(persona, serverUri, out)) {
            swarm.start();

            DemoEventWriter.write(out, new DemoEvent(
                    Instant.now().toString(),
                    "client",
                    persona.name(),
                    "event",
                    "client_ready",
                    persona.namespace(),
                    null,
                    null,
                    null,
                    persona.name(),
                    "swarm connected to " + serverUri + " with " + persona.connectionCount() + " clients",
                    null,
                    null,
                    null,
                    null));
            startCommandLoop(shutdown, swarm);
            shutdown.await();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down.
            }
        }
    }

    private static void startCommandLoop(CountDownLatch shutdown, DemoClientSwarm swarm) {
        Thread controlThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    DemoControlCommand command = DemoControlCommand.fromJson(line);
                    switch (command.type()) {
                        case "pause" -> swarm.pause();
                        case "resume" -> swarm.resume();
                        case "shutdown" -> shutdown.countDown();
                        default -> {
                            // Ignore commands this child does not own.
                        }
                    }
                }
            } catch (Exception ignored) {
                // Best effort: stdin may be closed when the parent exits.
            } finally {
                shutdown.countDown();
            }
        }, "wsserver-demo-client-commands");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    private static DemoPersona resolvePersona(String[] args, String presetId) {
        List<DemoPersona> personas = DemoScenarioDefinition.forPreset(presetId).personas();
        String requestedName = positionalArgs(args).isEmpty() ? personas.get(0).name() : positionalArgs(args).get(0);
        return personas.stream()
                .filter(persona -> persona.name().equals(requestedName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown demo persona: " + requestedName));
    }

    private static String resolveServerUri(String[] args) {
        List<String> positionalArgs = positionalArgs(args);
        if (positionalArgs.size() < 2 || positionalArgs.get(1).isBlank()) {
            return DEFAULT_SERVER_URI;
        }
        return positionalArgs.get(1);
    }

    private static String resolvePresetId(String[] args) {
        if (args == null) {
            return DemoPresetRegistry.defaultPresetId();
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--preset=")) {
                return DemoPresetRegistry.require(arg.substring("--preset=".length())).id();
            }
        }
        return DemoPresetRegistry.defaultPresetId();
    }

    private static List<String> positionalArgs(String[] args) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        return java.util.Arrays.stream(args)
                .filter(arg -> arg != null && !arg.isBlank())
                .filter(arg -> !arg.startsWith("--"))
                .toList();
    }
}

package io.streamfence.demo.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.ServerSocket;
import java.time.Duration;

public record DemoLauncherOptions(int serverPort, int managementPort, int consolePort, Duration startupTimeout) {

    private static final int MAX_TCP_PORT = 65_535;

    public DemoLauncherOptions {
        validatePort("serverPort", serverPort, false);
        validatePort("managementPort", managementPort, true);
        validatePort("consolePort", consolePort, true);
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("startupTimeout must be positive");
        }
    }

    public static DemoLauncherOptions defaults() {
        return new DemoLauncherOptions(9092, 9093, 9094, Duration.ofSeconds(30));
    }

    public static DemoLauncherOptions fromArgs(String[] args) {
        int serverPort = 9092;
        int managementPort = 9093;
        int consolePort = 9094;
        if (args != null) {
            for (String arg : args) {
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                if (arg.startsWith("--server-port=")) {
                    serverPort = Integer.parseInt(arg.substring("--server-port=".length()));
                } else if (arg.startsWith("--management-port=")) {
                    managementPort = Integer.parseInt(arg.substring("--management-port=".length()));
                } else if (arg.startsWith("--console-port=")) {
                    consolePort = Integer.parseInt(arg.substring("--console-port=".length()));
                }
            }
        }
        if (serverPort == 0) {
            serverPort = allocateFreePort();
        }
        if (managementPort == 0) {
            managementPort = allocateFreePort();
        }
        return new DemoLauncherOptions(serverPort, managementPort, consolePort, Duration.ofSeconds(30));
    }

    public URI serverUri() {
        return URI.create("http://127.0.0.1:" + serverPort);
    }

    private static int allocateFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to allocate an ephemeral server port", exception);
        }
    }

    private static void validatePort(String fieldName, int port, boolean allowZero) {
        int minimum = allowZero ? 0 : 1;
        if (port < minimum || port > MAX_TCP_PORT) {
            String range = allowZero ? "0.." + MAX_TCP_PORT : "1.." + MAX_TCP_PORT;
            throw new IllegalArgumentException(fieldName + " must be in range " + range);
        }
    }
}

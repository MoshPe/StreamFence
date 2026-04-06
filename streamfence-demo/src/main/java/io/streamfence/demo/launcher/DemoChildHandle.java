package io.streamfence.demo.launcher;

import io.streamfence.demo.runtime.DemoControlCommand;

public interface DemoChildHandle extends AutoCloseable {

    String processName();

    boolean isAlive();

    boolean hasExited();

    java.util.OptionalInt exitCode();

    void sendCommand(DemoControlCommand command);

    @Override
    void close();
}

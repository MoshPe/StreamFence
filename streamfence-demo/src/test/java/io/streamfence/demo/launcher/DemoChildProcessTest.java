package io.streamfence.demo.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.demo.runtime.DemoControlCommand;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class DemoChildProcessTest {

    @Test
    void sendCommandWritesJsonToChildStdin() throws Exception {
        List<String> stdout = new CopyOnWriteArrayList<>();
        DemoChildProcess child = DemoChildProcess.start(
                "echo",
                List.of(
                        Path.of(System.getenv("WINDIR"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                        "-Command",
                        "while (($line = [Console]::In.ReadLine()) -ne $null) { Write-Output $line }"),
                stdout::add);
        try {
            child.sendCommand(DemoControlCommand.pause());

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && stdout.stream().noneMatch(line -> line.contains("\"type\":\"pause\""))) {
                Thread.sleep(50L);
            }

            assertThat(stdout).anySatisfy(line -> assertThat(line).contains("\"type\":\"pause\""));
        } finally {
            child.close();
        }
    }

    @Test
    void closeTerminatesChildProcess() throws Exception {
        DemoChildProcess child = DemoChildProcess.start(
                "long-running",
                List.of(
                        Path.of(System.getenv("WINDIR"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                        "-Command",
                        "while (($line = [Console]::In.ReadLine()) -ne $null) { Start-Sleep -Milliseconds 100 } Start-Sleep -Seconds 15"),
                line -> {
                });

        child.close();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !child.hasExited()) {
            Thread.sleep(50L);
        }

        assertThat(child.hasExited()).isTrue();
    }
}

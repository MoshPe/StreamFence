package io.streamfence.demo.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.demo.dashboard.DashboardSnapshot;
import java.io.PrintStream;
import java.io.OutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DemoLauncherRuntimeIntegrationTest {

    @Test
    void throughputPresetProducesNonZeroByteRatesInDashboardSnapshot() throws Exception {
        DemoLauncherOptions options = DemoLauncherOptions.fromArgs(new String[] {
                "--server-port=0",
                "--management-port=0",
                "--console-port=0"
        });

        try (DemoLauncherRuntime runtime = DemoLauncherRuntime.start(
                options,
                new PrintStream(OutputStream.nullOutputStream()))) {
            assertThat(runtime.awaitReady(Duration.ofSeconds(30))).isTrue();

            DashboardSnapshot snapshot = awaitSnapshot(runtime, current ->
                    "throughput".equals(current.presetId())
                            && current.activeClients() >= 24
                            && current.totalMessages() > 0
                            && current.sendBytesPerSecond() > 0.0d
                            && current.receiveBytesPerSecond() > 0.0d,
                    Duration.ofSeconds(20));

            assertThat(snapshot.presetId()).isEqualTo("throughput");
            assertThat(snapshot.sendBytesPerSecond()).isPositive();
            assertThat(snapshot.receiveBytesPerSecond()).isPositive();
        }
    }

    @Test
    void bulkPresetProducesNonZeroByteRatesInDashboardSnapshot() throws Exception {
        DemoLauncherOptions options = DemoLauncherOptions.fromArgs(new String[] {
                "--server-port=0",
                "--management-port=0",
                "--console-port=0"
        });

        try (DemoLauncherRuntime runtime = DemoLauncherRuntime.start(
                options,
                new PrintStream(OutputStream.nullOutputStream()))) {
            assertThat(runtime.awaitReady(Duration.ofSeconds(30))).isTrue();

            runtime.applyPreset("bulk");

            DashboardSnapshot snapshot = awaitSnapshot(runtime, current ->
                    "bulk".equals(current.presetId())
                            && current.activeClients() >= 4
                            && current.totalMessages() > 0
                            && current.sendBytesPerSecond() > 0.0d
                            && current.receiveBytesPerSecond() > 0.0d,
                    Duration.ofSeconds(20));

            assertThat(snapshot.presetId()).isEqualTo("bulk");
            assertThat(snapshot.sendBytesPerSecond()).isPositive();
            assertThat(snapshot.receiveBytesPerSecond()).isPositive();
        }
    }

    private static DashboardSnapshot awaitSnapshot(
            DemoLauncherRuntime runtime,
            java.util.function.Predicate<DashboardSnapshot> predicate,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        DashboardSnapshot latest = runtime.dashboardSnapshot();
        while (System.nanoTime() < deadline) {
            latest = runtime.dashboardSnapshot();
            if (predicate.test(latest)) {
                return latest;
            }
            Thread.sleep(500L);
        }
        return latest;
    }
}

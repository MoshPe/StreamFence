package io.streamfence.demo.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DemoLauncherMainTest {

    @Test
    void startForTestClosesHandleWhenReadinessThrows() {
        AtomicBoolean closed = new AtomicBoolean();
        DemoLauncherMain.TestHandle handle = new DemoLauncherMain.TestHandle() {
            @Override
            public URI consoleUri() {
                return URI.create("http://127.0.0.1:9094");
            }

            @Override
            public URI serverUri() {
                return URI.create("http://127.0.0.1:9092");
            }

            @Override
            public boolean awaitReady() throws InterruptedException {
                throw new IllegalStateException("child exited");
            }

            @Override
            public List<io.streamfence.demo.runtime.DemoEvent> recentHistory() {
                return List.of();
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        assertThatThrownBy(() -> DemoLauncherMain.startForTest(
                new String[]{"--server-port=9092"},
                (options, out) -> handle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child exited");

        assertThat(closed.get()).isTrue();
    }
}

package io.streamfence.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PemReloadWatcherTest {

    @Test
    void emitsEventWhenCertMtimeChanges() throws Exception {
        Path temp = Files.createTempDirectory("pem-watcher-cert");
        try {
            Path cert = Files.writeString(temp.resolve("cert.pem"), "cert");
            Path key = Files.writeString(temp.resolve("key.pem"), "key");
            Files.setLastModifiedTime(cert, FileTime.from(Instant.ofEpochSecond(1_000_000)));
            Files.setLastModifiedTime(key, FileTime.from(Instant.ofEpochSecond(1_000_000)));

            List<String> changed = new ArrayList<>();
            PemReloadWatcher watcher = new PemReloadWatcher(cert, key, changed::add);

            watcher.check();
            assertThat(changed).isEmpty();

            Files.setLastModifiedTime(cert, FileTime.from(Instant.ofEpochSecond(2_000_000)));
            watcher.check();
            assertThat(changed).hasSize(1).first().asString().endsWith("cert.pem");

            watcher.check();
            assertThat(changed).hasSize(1);
        } finally {
            deleteRecursively(temp);
        }
    }

    @Test
    void emitsEventWhenKeyMtimeChanges() throws Exception {
        Path temp = Files.createTempDirectory("pem-watcher-key");
        try {
            Path cert = Files.writeString(temp.resolve("cert.pem"), "cert");
            Path key = Files.writeString(temp.resolve("key.pem"), "key");
            Files.setLastModifiedTime(cert, FileTime.from(Instant.ofEpochSecond(1_000_000)));
            Files.setLastModifiedTime(key, FileTime.from(Instant.ofEpochSecond(1_000_000)));

            List<String> changed = new ArrayList<>();
            PemReloadWatcher watcher = new PemReloadWatcher(cert, key, changed::add);

            Files.setLastModifiedTime(key, FileTime.from(Instant.ofEpochSecond(2_000_000)));
            watcher.check();
            assertThat(changed).hasSize(1).first().asString().endsWith("key.pem");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
    }
}

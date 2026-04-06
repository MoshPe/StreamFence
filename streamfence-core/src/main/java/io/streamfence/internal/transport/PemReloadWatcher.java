package io.streamfence.internal.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the configured PEM certificate and key files for mtime changes.
 * When either file changes, the watcher logs a WARN event and notifies a
 * listener so operators can page themselves to restart the server.
 *
 * <p>Hot reload of the TLS material inside netty-socketio 2.0.14 is not
 * currently supported by the library API, so this is strictly a detection
 * and signalling mechanism.
 *
 * <p>The watcher is designed to run on the existing
 * {@link java.util.concurrent.ScheduledExecutorService} — construct once,
 * then schedule {@link #check()} at a fixed rate. Construction reads the
 * initial mtime; subsequent {@link #check()} calls compare against it and
 * fire the listener on each change.
 */
public final class PemReloadWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(PemReloadWatcher.class);

    private final Path certPath;
    private final Path keyPath;
    private final Consumer<String> onChange;
    private FileTime certMtime;
    private FileTime keyMtime;

    public PemReloadWatcher(Path certPath, Path keyPath, Consumer<String> onChange) {
        this.certPath = Objects.requireNonNull(certPath, "certPath");
        this.keyPath = Objects.requireNonNull(keyPath, "keyPath");
        this.onChange = onChange == null ? ignored -> { } : onChange;
        this.certMtime = readMtime(certPath);
        this.keyMtime = readMtime(keyPath);
    }

    /**
     * Compares current mtimes to the last observed values. Fires the
     * listener once per file whose mtime advanced. Safe to call from a
     * scheduler thread.
     */
    public void check() {
        FileTime cert = readMtime(certPath);
        if (cert != null && !cert.equals(certMtime)) {
            certMtime = cert;
            fire(certPath);
        }
        FileTime key = readMtime(keyPath);
        if (key != null && !key.equals(keyMtime)) {
            keyMtime = key;
            fire(keyPath);
        }
    }

    private void fire(Path path) {
        LOG.warn("event=tls_material_changed file={} action=restart_required", path);
        try {
            onChange.accept(path.toString());
        } catch (RuntimeException exception) {
            LOG.warn("event=tls_material_listener_failed file={} error={}", path, exception.getMessage());
        }
    }

    private static FileTime readMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return null;
        }
    }
}

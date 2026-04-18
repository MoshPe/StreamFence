package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpillFileStoreTest {

    @Test
    void drainIgnoresPartialTempFiles() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        Path committedFile = spillDirectory.resolve("00000001.spill");
        Path partialTempFile = spillDirectory.resolve("00000002.tmp");
        Files.writeString(committedFile, """
                {"messageId":"spill-1","namespace":"/spill","topic":"prices","payload":{"id":"spill-1"}}
                """.trim());
        Files.writeString(partialTempFile, "partial spill payload");

        Object store = newStore(spillDirectory);

        assertThat(drainMessageIds(store)).containsExactly("spill-1");
        assertThat(Files.exists(committedFile)).isFalse();
        assertThat(Files.exists(partialTempFile)).isTrue();
    }

    @Test
    void cleanupRemovesTheSpillDirectory() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        Path committedFile = spillDirectory.resolve("00000001.spill");
        Path partialTempFile = spillDirectory.resolve("00000002.tmp");
        Files.writeString(committedFile, """
                {"messageId":"spill-1","namespace":"/spill","topic":"prices","payload":{"id":"spill-1"}}
                """.trim());
        Files.writeString(partialTempFile, "partial spill payload");

        Object store = newStore(spillDirectory);
        cleanup(store);

        assertThat(Files.exists(spillDirectory)).isFalse();
        assertThat(Files.exists(committedFile)).isFalse();
        assertThat(Files.exists(partialTempFile)).isFalse();
    }

    // ── new coverage tests ────────────────────────────────────────────────────

    @Test
    void drainReturnsEmptyListWhenDirectoryNoLongerExists() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        SpillFileStore store = new SpillFileStore(spillDirectory);
        // Remove directory externally to exercise the early-return guard in drain()
        try (var stream = Files.walk(spillDirectory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }

        assertThat(store.drain()).isEmpty();
    }

    @Test
    void cleanupIsNoOpWhenDirectoryAlreadyRemoved() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        SpillFileStore store = new SpillFileStore(spillDirectory);
        store.cleanup();
        // Second call must not throw even though the directory is gone
        store.cleanup();
        assertThat(Files.exists(spillDirectory)).isFalse();
    }

    @Test
    void discoverNextSequenceContinuesFromHighestExistingFile() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        // Pre-populate with sequences 3 and 7 so the next should be 8
        Files.writeString(spillDirectory.resolve("00000003.spill"),
                "{\"messageId\":\"pre-3\",\"namespace\":\"/test\",\"topic\":\"t\"}");
        Files.writeString(spillDirectory.resolve("00000007.spill"),
                "{\"messageId\":\"pre-7\",\"namespace\":\"/test\",\"topic\":\"t\"}");

        SpillFileStore store = new SpillFileStore(spillDirectory);
        store.append(makeEntry("new-entry", 32));

        List<Path> spills;
        try (var stream = Files.list(spillDirectory)) {
            spills = stream
                    .filter(p -> p.getFileName().toString().endsWith(".spill"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        // Should have 3 files; the newest must carry sequence 8
        assertThat(spills).hasSize(3);
        assertThat(spills.get(2).getFileName().toString()).isEqualTo("00000008.spill");
        store.cleanup();
    }

    @Test
    void appendAndDrainPreservesMessageIds() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        SpillFileStore store = new SpillFileStore(spillDirectory);

        store.append(makeEntry("alpha", 64));
        store.append(makeEntry("beta", 64));

        List<LaneEntry> drained = store.drain();
        assertThat(drained).extracting(LaneEntry::messageId)
                .containsExactly("alpha", "beta");
        // Files must be deleted after drain
        try (var stream = Files.list(spillDirectory)) {
            assertThat(stream.filter(p -> p.getFileName().toString().endsWith(".spill")).count())
                    .isZero();
        }
        store.cleanup();
    }

    @Test
    void drainFallsBackToDefaultsForSpillFileWithMissingOptionalFields() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        // Write a minimal JSON with only the messageId field — all others must use defaults
        Files.writeString(spillDirectory.resolve("00000001.spill"), "{\"messageId\":\"minimal\"}");

        SpillFileStore store = new SpillFileStore(spillDirectory);
        List<LaneEntry> drained = store.drain();

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).messageId()).isEqualTo("minimal");
        assertThat(drained.get(0).namespace()).isEqualTo("/spill");
        assertThat(drained.get(0).topic()).isEqualTo("spill");
    }

    @Test
    void appendEntryWithNoEnvelopeInEventArgumentsSerializesNullPayload() throws Exception {
        Path spillDirectory = Files.createTempDirectory("spill-file-store-test");
        SpillFileStore store = new SpillFileStore(spillDirectory);

        // OutboundTopicMessage with an empty eventArguments array — no TopicMessageEnvelope
        TopicMessageMetadata metadata = new TopicMessageMetadata("/test", "topic", "no-env", false);
        OutboundTopicMessage msg = new OutboundTopicMessage(
                "topic-message", metadata, new Object[0], 16);
        LaneEntry entry = new LaneEntry(new PublishedMessage(msg, null));

        store.append(entry);
        List<LaneEntry> drained = store.drain();

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).messageId()).isEqualTo("no-env");
        store.cleanup();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LaneEntry makeEntry(String messageId, long bytes) {
        TopicPolicy policy = new TopicPolicy(
                "/test", "topic",
                DeliveryMode.BEST_EFFORT, OverflowAction.SPILL_TO_DISK,
                4, 1024, 1000, 0, false, true, false, 1);
        TopicMessageMetadata metadata = new TopicMessageMetadata(
                policy.namespace(), policy.topic(), messageId, false);
        ObjectNode payload = MAPPER.createObjectNode().put("id", messageId);
        TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, payload);
        OutboundTopicMessage msg = new OutboundTopicMessage(
                "topic-message", metadata, new Object[]{envelope}, bytes);
        return new LaneEntry(new PublishedMessage(msg, null));
    }

    // ── original reflection-based helpers ────────────────────────────────────

    private static Object newStore(Path spillDirectory) throws Exception {
        Class<?> storeType;
        try {
            storeType = Class.forName("io.streamfence.internal.delivery.SpillFileStore");
        } catch (ClassNotFoundException exception) {
            fail("Missing io.streamfence.internal.delivery.SpillFileStore");
            return null;
        }

        try {
            return storeType.getConstructor(Path.class).newInstance(spillDirectory);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("SpillFileStore should expose a Path constructor", exception);
        }
    }

    private static List<String> drainMessageIds(Object store) throws Exception {
        Object drained = invokeExact(store, "drain");
        if (!(drained instanceof List<?> records)) {
            throw new AssertionError("SpillFileStore.drain() should return a List<LaneEntry>");
        }
        List<String> messageIds = new java.util.ArrayList<>();
        for (Object record : records) {
            if (!(record instanceof LaneEntry laneEntry)) {
                throw new AssertionError("SpillFileStore.drain() should return LaneEntry values");
            }
            messageIds.add(laneEntry.messageId());
        }
        return messageIds;
    }

    private static void cleanup(Object store) throws Exception {
        invokeExact(store, "cleanup");
    }

    private static Object invokeExact(Object target, String methodName) throws Exception {
        Class<?> type = target.getClass();
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == 0) {
                try {
                    return method.invoke(target);
                } catch (InvocationTargetException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof Exception exceptionCause) {
                        throw exceptionCause;
                    }
                    throw exception;
                }
            }
        }
        throw new AssertionError("Missing expected SpillFileStore method: " + methodName);
    }
}

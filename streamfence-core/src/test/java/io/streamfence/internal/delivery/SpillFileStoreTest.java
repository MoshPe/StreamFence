package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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

package io.streamfence.internal.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.streamfence.internal.protocol.OutboundTopicMessage;
import io.streamfence.internal.protocol.TopicMessageEnvelope;
import io.streamfence.internal.protocol.TopicMessageMetadata;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpillFileStore {

    private static final Pattern FILE_PATTERN = Pattern.compile("^(\\d+)\\.(spill|tmp)$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path rootDirectory;
    private long nextSequence;

    public SpillFileStore(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create spill directory " + this.rootDirectory, exception);
        }
        this.nextSequence = discoverNextSequence();
    }

    public synchronized List<LaneEntry> drain() {
        if (!Files.exists(rootDirectory)) {
            return List.of();
        }

        List<Path> committedFiles;
        try (var stream = Files.list(rootDirectory)) {
            committedFiles = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".spill"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list spill files in " + rootDirectory, exception);
        }

        List<LaneEntry> entries = new ArrayList<>(committedFiles.size());
        for (Path committedFile : committedFiles) {
            entries.add(readEntry(committedFile));
            try {
                Files.deleteIfExists(committedFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to delete spill file " + committedFile, exception);
            }
        }
        return List.copyOf(entries);
    }

    public synchronized void cleanup() {
        if (!Files.exists(rootDirectory)) {
            return;
        }

        try (var stream = Files.walk(rootDirectory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete spill path " + path, exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clean up spill directory " + rootDirectory, exception);
        }
    }

    synchronized void append(LaneEntry laneEntry) {
        SpillRecord record = toRecord(laneEntry);
        long sequence = nextSequence++;
        Path tempFile = rootDirectory.resolve(String.format("%08d.tmp", sequence));
        Path committedFile = rootDirectory.resolve(String.format("%08d.spill", sequence));
        try {
            OBJECT_MAPPER.writeValue(tempFile.toFile(), record);
            try {
                Files.move(tempFile, committedFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, committedFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("Failed to append spill record to " + committedFile, exception);
        }
    }

    private long discoverNextSequence() {
        long highestSequence = 0;
        try (var stream = Files.list(rootDirectory)) {
            for (Path path : stream.toList()) {
                Matcher matcher = FILE_PATTERN.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    highestSequence = Math.max(highestSequence, Long.parseLong(matcher.group(1)));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan spill directory " + rootDirectory, exception);
        }
        return highestSequence + 1;
    }

    private LaneEntry readEntry(Path committedFile) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(committedFile.toFile());
            String messageId = text(root, "messageId", stripExtension(committedFile.getFileName().toString()));
            String namespace = text(root, "namespace", "/spill");
            String topic = text(root, "topic", "spill");
            boolean ackRequired = root.path("ackRequired").asBoolean(false);
            long estimatedBytes = root.path("estimatedBytes").asLong(Math.max(1L, Files.size(committedFile)));
            Object payload = root.has("payload") && !root.get("payload").isNull()
                    ? OBJECT_MAPPER.treeToValue(root.get("payload"), Object.class)
                    : null;
            String eventName = text(root, "eventName", "topic-message");

            TopicMessageMetadata metadata = new TopicMessageMetadata(namespace, topic, messageId, ackRequired);
            TopicMessageEnvelope envelope = new TopicMessageEnvelope(metadata, payload);
            OutboundTopicMessage outboundTopicMessage = new OutboundTopicMessage(
                    eventName,
                    metadata,
                    new Object[]{envelope},
                    estimatedBytes);
            return new LaneEntry(new PublishedMessage(outboundTopicMessage, null));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read spill file " + committedFile, exception);
        }
    }

    private SpillRecord toRecord(LaneEntry laneEntry) {
        TopicMessageEnvelope envelope = null;
        Object[] eventArguments = laneEntry.outboundMessage().eventArguments();
        if (eventArguments.length > 0 && eventArguments[0] instanceof TopicMessageEnvelope topicMessageEnvelope) {
            envelope = topicMessageEnvelope;
        }
        return new SpillRecord(
                laneEntry.outboundMessage().eventName(),
                laneEntry.namespace(),
                laneEntry.topic(),
                laneEntry.messageId(),
                laneEntry.ackRequired(),
                laneEntry.estimatedBytes(),
                envelope == null ? null : envelope.payload()
        );
    }

    private static String text(JsonNode root, String fieldName, String defaultValue) {
        JsonNode node = root.get(fieldName);
        return node == null || node.isNull() || node.asText().isBlank() ? defaultValue : node.asText();
    }

    private static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private record SpillRecord(
            String eventName,
            String namespace,
            String topic,
            String messageId,
            boolean ackRequired,
            long estimatedBytes,
            Object payload
    ) {
    }
}

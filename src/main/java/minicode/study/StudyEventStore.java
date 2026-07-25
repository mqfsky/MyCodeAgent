package minicode.study;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only JSONL storage for study activity and quiz state events.
 *
 * <p>The store assigns schema version and sequence numbers while holding an exclusive file
 * lock. Repeating an {@code eventId} is idempotent and returns the already stored event.
 * A malformed final line is ignored only when it has no terminating newline; a later append
 * truncates that crash tail before writing the next complete event.</p>
 */
public final class StudyEventStore {
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ConcurrentMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Path, ReentrantLock> OPERATION_JVM_LOCKS = new ConcurrentHashMap<>();
    private static final List<String> REQUIRED_TEXT_FIELDS =
            List.of("eventId", "type", "sessionId", "quizId", "timestamp");

    private final Path home;
    private final Path studyDirectory;
    private final Path eventsPath;
    private final Path operationLockPath;
    private final Object jvmLock;
    private final ReentrantLock operationJvmLock;

    public StudyEventStore(Path home) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.studyDirectory = this.home.resolve("study");
        this.eventsPath = studyDirectory.resolve("events.jsonl");
        this.operationLockPath = studyDirectory.resolve(".operations.lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(eventsPath, ignored -> new Object());
        this.operationJvmLock = OPERATION_JVM_LOCKS.computeIfAbsent(
                operationLockPath, ignored -> new ReentrantLock());
    }

    /**
     * Locks one complete Study domain operation across threads and processes.
     *
     * <p>The event-file lock used by {@link #append(ObjectNode)} protects one physical
     * append. This separate lock protects the higher-level read/validate/append transaction,
     * so two CodeAgent processes cannot both make decisions from the same stale projection.</p>
     */
    OperationLock acquireOperationLock() {
        operationJvmLock.lock();
        FileChannel channel = null;
        boolean acquired = false;
        try {
            ensureStudyDirectory();
            rejectUnsafeExistingTarget(operationLockPath, "study operation lock");
            channel = FileChannel.open(
                    operationLockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
            FileLock fileLock = channel.lock();
            OperationLock result = new OperationLock(channel, fileLock, operationJvmLock);
            acquired = true;
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Unable to acquire study operation lock: " + operationLockPath, exception);
        } finally {
            if (!acquired) {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // Preserve the acquisition failure.
                    }
                }
                operationJvmLock.unlock();
            }
        }
    }

    /**
     * Reads all complete events in sequence order.
     *
     * <p>A malformed, non-newline-terminated crash tail is ignored. Any malformed complete
     * line, or any structurally invalid event, fails the whole read.</p>
     */
    public List<ObjectNode> readAll() {
        synchronized (jvmLock) {
            if (!verifyExistingStudyDirectory()) {
                return List.of();
            }
            if (!Files.exists(eventsPath, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            requireSafeRegularFile(eventsPath, "study event log");
            try (FileChannel channel = FileChannel.open(
                    eventsPath,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                ParsedLog parsed = parseLog(readBytes(channel));
                return copyEvents(parsed.events());
            } catch (NoSuchFileException exception) {
                return List.of();
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read study event log: " + eventsPath, exception);
            }
        }
    }

    /**
     * Appends one event, assigning {@code schemaVersion=1} and the next monotonic sequence.
     *
     * <p>The input must provide non-blank {@code eventId}, {@code type}, {@code sessionId},
     * {@code quizId}, and {@code timestamp}. Caller-provided schema and sequence fields are
     * overwritten. Reusing an existing event id returns the original stored event without
     * appending another line.</p>
     */
    public ObjectNode append(ObjectNode event) {
        ObjectNode candidate = Objects.requireNonNull(event, "event").deepCopy();
        validateRequiredInput(candidate);

        synchronized (jvmLock) {
            ensureStudyDirectory();
            rejectUnsafeExistingTarget(eventsPath, "study event log");
            try (FileChannel channel = FileChannel.open(
                    eventsPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                ParsedLog parsed = parseLog(readBytes(channel));
                String eventId = candidate.get("eventId").asText();
                for (ObjectNode existing : parsed.events()) {
                    if (eventId.equals(existing.get("eventId").asText())) {
                        return existing.deepCopy();
                    }
                }

                long previousSequence = parsed.events().isEmpty()
                        ? 0L
                        : parsed.events().getLast().get("sequence").longValue();
                if (previousSequence == Long.MAX_VALUE) {
                    throw new IllegalStateException("Study event sequence is exhausted");
                }

                candidate.put("schemaVersion", SCHEMA_VERSION);
                candidate.put("sequence", previousSequence + 1L);
                byte[] encoded = serializeLine(candidate);

                if (parsed.ignoredCrashTail()) {
                    channel.truncate(parsed.validByteCount());
                }
                channel.position(parsed.validByteCount());
                if (parsed.validFinalLineWithoutNewline()) {
                    writeFully(channel, new byte[]{'\n'});
                }
                writeFully(channel, encoded);
                channel.force(true);
                return candidate.deepCopy();
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to append study event: " + eventsPath, exception);
            }
        }
    }

    public Path path() {
        return eventsPath;
    }

    private ParsedLog parseLog(byte[] bytes) {
        List<ObjectNode> events = new ArrayList<>();
        Set<String> eventIds = new HashSet<>();
        long previousSequence = 0L;
        int lineStart = 0;
        int lineNumber = 1;
        int validByteCount = 0;

        while (lineStart < bytes.length) {
            int newline = indexOfNewline(bytes, lineStart);
            boolean terminated = newline >= 0;
            int lineEnd = terminated ? newline : bytes.length;
            byte[] line = Arrays.copyOfRange(bytes, lineStart, lineEnd);

            ObjectNode event;
            if (terminated) {
                event = parseCompleteLine(line, lineNumber);
            } else {
                try {
                    event = parseJsonObject(line);
                } catch (IOException exception) {
                    return new ParsedLog(
                            List.copyOf(events),
                            lineStart,
                            false,
                            true
                    );
                }
            }

            previousSequence = validateStoredEvent(event, previousSequence, eventIds, lineNumber);
            events.add(event);
            if (!terminated) {
                return new ParsedLog(
                        List.copyOf(events),
                        bytes.length,
                        true,
                        false
                );
            }

            validByteCount = newline + 1;
            lineStart = newline + 1;
            lineNumber++;
        }
        return new ParsedLog(List.copyOf(events), validByteCount, false, false);
    }

    private static ObjectNode parseCompleteLine(byte[] line, int lineNumber) {
        try {
            return parseJsonObject(line);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Invalid study event JSON at line " + lineNumber, exception);
        }
    }

    private static ObjectNode parseJsonObject(byte[] line) throws IOException {
        JsonNode parsed = MAPPER.readTree(line);
        if (!(parsed instanceof ObjectNode object)) {
            throw new IllegalStateException("Study event line must contain one JSON object");
        }
        return object;
    }

    private static long validateStoredEvent(ObjectNode event,
                                            long previousSequence,
                                            Set<String> eventIds,
                                            int lineNumber) {
        JsonNode schema = event.get("schemaVersion");
        if (schema == null || !schema.isIntegralNumber() || schema.intValue() != SCHEMA_VERSION) {
            throw invalidStoredEvent(lineNumber, "schemaVersion must be " + SCHEMA_VERSION);
        }
        JsonNode sequence = event.get("sequence");
        if (sequence == null || !sequence.isIntegralNumber() || sequence.longValue() <= previousSequence) {
            throw invalidStoredEvent(lineNumber, "sequence must increase monotonically");
        }
        for (String field : REQUIRED_TEXT_FIELDS) {
            requireText(event, field, "study event at line " + lineNumber);
        }
        String eventId = event.get("eventId").asText();
        if (!eventIds.add(eventId)) {
            throw invalidStoredEvent(lineNumber, "duplicate eventId " + eventId);
        }
        return sequence.longValue();
    }

    private static void validateRequiredInput(ObjectNode event) {
        for (String field : REQUIRED_TEXT_FIELDS) {
            requireText(event, field, "study event");
        }
    }

    private static String requireText(ObjectNode event, String field, String description) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(description + " requires non-blank " + field);
        }
        return value.asText();
    }

    private static IllegalStateException invalidStoredEvent(int lineNumber, String reason) {
        return new IllegalStateException("Invalid study event at line " + lineNumber + ": " + reason);
    }

    private static byte[] serializeLine(ObjectNode event) {
        try {
            return (MAPPER.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize study event", exception);
        }
    }

    private static int indexOfNewline(byte[] bytes, int start) {
        for (int index = start; index < bytes.length; index++) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static byte[] readBytes(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Study event log is too large to read");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        channel.position(0L);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
        }
        return buffer.array();
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static List<ObjectNode> copyEvents(List<ObjectNode> events) {
        return events.stream().map(ObjectNode::deepCopy).toList();
    }

    private boolean verifyExistingStudyDirectory() {
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(home)) {
            throw new IllegalStateException("Study home is not a directory: " + home);
        }
        if (!Files.exists(studyDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        rejectSymbolicLink(studyDirectory, "study directory");
        if (!Files.isDirectory(studyDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Study path is not a directory: " + studyDirectory);
        }
        return true;
    }

    private void ensureStudyDirectory() {
        try {
            if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(home);
            }
            if (!Files.isDirectory(home)) {
                throw new IllegalStateException("Study home is not a directory: " + home);
            }
            if (Files.exists(studyDirectory, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymbolicLink(studyDirectory, "study directory");
                if (!Files.isDirectory(studyDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Study path is not a directory: " + studyDirectory);
                }
                return;
            }
            try {
                Files.createDirectory(studyDirectory);
            } catch (FileAlreadyExistsException ignored) {
                rejectSymbolicLink(studyDirectory, "study directory");
                if (!Files.isDirectory(studyDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Study path is not a directory: " + studyDirectory);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to create study directory: " + studyDirectory, exception);
        }
    }

    private static void rejectUnsafeExistingTarget(Path target, String description) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireSafeRegularFile(target, description);
    }

    private static void requireSafeRegularFile(Path target, String description) {
        rejectSymbolicLink(target, description);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(description + " is not a regular file: " + target);
        }
    }

    private static void rejectSymbolicLink(Path target, String description) {
        if (Files.isSymbolicLink(target)) {
            throw new IllegalStateException("Symbolic links are not allowed for " + description + ": " + target);
        }
    }

    private static record ParsedLog(List<ObjectNode> events,
                                    int validByteCount,
                                    boolean validFinalLineWithoutNewline,
                                    boolean ignoredCrashTail) {
    }

    static final class OperationLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock fileLock;
        private final ReentrantLock jvmLock;
        private boolean closed;

        private OperationLock(FileChannel channel, FileLock fileLock, ReentrantLock jvmLock) {
            this.channel = channel;
            this.fileLock = fileLock;
            this.jvmLock = jvmLock;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                fileLock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            } finally {
                jvmLock.unlock();
            }
            if (failure != null) {
                throw new UncheckedIOException("Unable to release study operation lock", failure);
            }
        }
    }
}

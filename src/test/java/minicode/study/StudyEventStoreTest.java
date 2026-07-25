package minicode.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StudyEventStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void fixedPathReadDoesNotCreateMissingStorage() {
        Path home = tempDir.resolve("home");
        StudyEventStore store = new StudyEventStore(home);

        assertTrue(store.readAll().isEmpty());
        assertEquals(home.toAbsolutePath().normalize().resolve("study/events.jsonl"), store.path());
        assertFalse(Files.exists(home));
    }

    @Test
    void appendAddsSchemaAndMonotonicSequenceWithoutMutatingCaller() {
        StudyEventStore store = new StudyEventStore(tempDir.resolve("home"));
        ObjectNode firstInput = event("event-1").put("detail", "first");

        ObjectNode first = store.append(firstInput);
        ObjectNode second = store.append(event("event-2").put("detail", "second"));

        assertFalse(firstInput.has("schemaVersion"));
        assertFalse(firstInput.has("sequence"));
        assertEquals(StudyEventStore.SCHEMA_VERSION, first.get("schemaVersion").asInt());
        assertEquals(1L, first.get("sequence").asLong());
        assertEquals(2L, second.get("sequence").asLong());
        List<ObjectNode> stored = store.readAll();
        assertEquals(List.of("event-1", "event-2"),
                stored.stream().map(node -> node.get("eventId").asText()).toList());
        assertEquals(List.of(1L, 2L),
                stored.stream().map(node -> node.get("sequence").asLong()).toList());
    }

    @Test
    void duplicateEventIdIsIdempotentAndDoesNotConsumeASequence() throws Exception {
        StudyEventStore store = new StudyEventStore(tempDir.resolve("home"));
        ObjectNode original = store.append(event("same-id").put("detail", "original"));

        ObjectNode duplicate = store.append(event("same-id")
                .put("type", "DIFFERENT_TYPE")
                .put("detail", "must-not-replace"));

        assertEquals(original.get("eventId").asText(), duplicate.get("eventId").asText());
        assertEquals(original.get("detail").asText(), duplicate.get("detail").asText());
        assertEquals(original.get("sequence").asLong(), duplicate.get("sequence").asLong());
        assertEquals(1, store.readAll().size());
        assertEquals(1L, store.readAll().getFirst().get("sequence").asLong());
        assertEquals(1L, Files.readAllLines(store.path()).size());
    }

    @Test
    void appendRequiresStableIdentityAndRoutingFields() {
        StudyEventStore store = new StudyEventStore(tempDir.resolve("home"));
        for (String field : List.of("eventId", "type", "sessionId", "quizId", "timestamp")) {
            ObjectNode missing = event("event-" + field);
            missing.remove(field);

            IllegalArgumentException exception =
                    assertThrows(IllegalArgumentException.class, () -> store.append(missing));
            assertTrue(exception.getMessage().contains(field), exception.getMessage());
        }
    }

    @Test
    void malformedFinalUnterminatedTailIsIgnoredAndRepairedByNextAppend() throws Exception {
        StudyEventStore store = new StudyEventStore(tempDir.resolve("home"));
        store.append(event("event-1"));
        Files.writeString(
                store.path(),
                "{\"eventId\":\"crash-tail\"",
                StandardOpenOption.APPEND
        );

        assertEquals(1, store.readAll().size());

        ObjectNode second = store.append(event("event-2"));

        assertEquals(2L, second.get("sequence").asLong());
        assertEquals(List.of("event-1", "event-2"),
                store.readAll().stream().map(node -> node.get("eventId").asText()).toList());
        String content = Files.readString(store.path());
        assertFalse(content.contains("crash-tail"), content);
        assertTrue(content.endsWith("\n"), content);
    }

    @Test
    void malformedCompleteLineIsNeverSilentlyIgnored() throws Exception {
        StudyEventStore store = new StudyEventStore(tempDir.resolve("home"));
        store.append(event("event-1"));
        Files.writeString(
                store.path(),
                "{\"broken\"\n",
                StandardOpenOption.APPEND
        );

        assertThrows(IllegalStateException.class, store::readAll);
        assertThrows(IllegalStateException.class, () -> store.append(event("event-2")));
    }

    @Test
    void validFinalLineWithoutNewlineIsReadAndSeparatedBeforeNextAppend() throws Exception {
        StudyEventStore store = new StudyEventStore(tempDir.resolve("home"));
        store.append(event("event-1"));
        String terminated = Files.readString(store.path());
        Files.writeString(
                store.path(),
                terminated.substring(0, terminated.length() - 1),
                StandardOpenOption.TRUNCATE_EXISTING
        );

        assertEquals(1, store.readAll().size());
        store.append(event("event-2"));

        assertEquals(2, store.readAll().size());
        assertEquals(2, Files.readAllLines(store.path()).size());
    }

    @Test
    void storedSequencesMustIncreaseAndStoredEventIdsMustBeUnique() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path study = Files.createDirectories(home.resolve("study"));
        StudyEventStore store = new StudyEventStore(home);
        ObjectNode first = persistedEvent("event-1", 2L);
        ObjectNode nonIncreasing = persistedEvent("event-2", 1L);
        Files.writeString(
                study.resolve("events.jsonl"),
                MAPPER.writeValueAsString(first) + "\n" + MAPPER.writeValueAsString(nonIncreasing) + "\n"
        );
        assertThrows(IllegalStateException.class, store::readAll);

        ObjectNode duplicateId = persistedEvent("event-1", 3L);
        Files.writeString(
                study.resolve("events.jsonl"),
                MAPPER.writeValueAsString(first) + "\n" + MAPPER.writeValueAsString(duplicateId) + "\n"
        );
        assertThrows(IllegalStateException.class, store::readAll);
    }

    @Test
    void concurrentStoreInstancesAssignEachEventOneSequence() throws Exception {
        Path home = tempDir.resolve("home");
        int eventCount = 24;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<ObjectNode>> futures = new ArrayList<>();
            for (int index = 0; index < eventCount; index++) {
                int eventIndex = index;
                futures.add(executor.submit(() ->
                        new StudyEventStore(home).append(event("event-" + eventIndex))));
            }
            for (Future<ObjectNode> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<ObjectNode> events = new StudyEventStore(home).readAll();
        assertEquals(eventCount, events.size());
        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, eventCount).boxed().toList(),
                events.stream().map(node -> node.get("sequence").asLong()).toList()
        );
        assertEquals(eventCount,
                events.stream().map(node -> node.get("eventId").asText()).distinct().count());
    }

    @Test
    void symbolicEventLogIsRejectedWithoutTouchingItsDestination() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path study = Files.createDirectories(home.resolve("study"));
        Path outside = tempDir.resolve("outside.jsonl");
        Files.writeString(outside, "outside");
        createSymbolicLinkOrSkip(study.resolve("events.jsonl"), outside);
        StudyEventStore store = new StudyEventStore(home);

        assertThrows(IllegalStateException.class, store::readAll);
        assertThrows(IllegalStateException.class, () -> store.append(event("event-1")));
        assertEquals("outside", Files.readString(outside));
    }

    private static ObjectNode event(String eventId) {
        return MAPPER.createObjectNode()
                .put("eventId", eventId)
                .put("type", "QUIZ_STARTED")
                .put("sessionId", "session-1")
                .put("quizId", "quiz-1")
                .put("timestamp", "2026-07-25T00:00:00Z");
    }

    private static ObjectNode persistedEvent(String eventId, long sequence) {
        return event(eventId)
                .put("schemaVersion", StudyEventStore.SCHEMA_VERSION)
                .put("sequence", sequence);
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }
    }
}

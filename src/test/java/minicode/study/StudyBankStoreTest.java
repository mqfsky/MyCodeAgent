package minicode.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StudyBankStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void fixedPathReadDoesNotCreateMissingStorage() {
        Path home = tempDir.resolve("home");
        StudyBankStore store = new StudyBankStore(home);

        Optional<ObjectNode> bank = store.read();

        assertTrue(bank.isEmpty());
        assertEquals(home.toAbsolutePath().normalize().resolve("study/bank.json"), store.path());
        assertFalse(Files.exists(home));
    }

    @Test
    void writeAtomicallyReplacesTheWholeBankAndDoesNotRetainCallerMutability() throws Exception {
        Path home = tempDir.resolve("home");
        StudyBankStore store = new StudyBankStore(home);
        ObjectNode first = MAPPER.createObjectNode()
                .put("schemaVersion", 1)
                .put("sourceId", "java-v1");
        first.putArray("questions")
                .addObject()
                .put("questionId", "q-1")
                .put("question", "什么是 JVM？");

        store.write(first);
        first.put("sourceId", "mutated-after-write");

        ObjectNode storedFirst = store.read().orElseThrow();
        assertEquals("java-v1", storedFirst.get("sourceId").asText());
        assertTrue(Files.readString(store.path()).endsWith("\n"));

        ObjectNode second = MAPPER.createObjectNode()
                .put("schemaVersion", 1)
                .put("sourceId", "redis-v1");
        second.putArray("questions")
                .addObject()
                .put("questionId", "q-2")
                .put("question", "什么是缓存穿透？");
        store.write(second);

        ObjectNode storedSecond = store.read().orElseThrow();
        assertEquals("redis-v1", storedSecond.get("sourceId").asText());
        assertEquals(1, storedSecond.withArray("questions").size());
        assertEquals("q-2", storedSecond.withArray("questions").get(0).get("questionId").asText());
        try (var files = Files.list(store.path().getParent())) {
            assertEquals(1L, files.count(), "temporary bank files must not remain after replacement");
        }
    }

    @Test
    void readRejectsMalformedOrNonObjectBankJson() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path study = Files.createDirectories(home.resolve("study"));
        StudyBankStore store = new StudyBankStore(home);

        Files.writeString(study.resolve("bank.json"), "[1, 2, 3]");
        assertThrows(IllegalStateException.class, store::read);

        Files.writeString(study.resolve("bank.json"), "{\"broken\"");
        assertThrows(IllegalStateException.class, store::read);
    }

    @Test
    void symbolicBankTargetIsRejectedWithoutTouchingItsDestination() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path study = Files.createDirectories(home.resolve("study"));
        Path outside = tempDir.resolve("outside.json");
        Files.writeString(outside, "{\"marker\":\"outside\"}");
        createSymbolicLinkOrSkip(study.resolve("bank.json"), outside);
        StudyBankStore store = new StudyBankStore(home);

        assertThrows(IllegalStateException.class, store::read);
        assertThrows(IllegalStateException.class,
                () -> store.write(MAPPER.createObjectNode().put("schemaVersion", 1)));
        assertEquals("{\"marker\":\"outside\"}", Files.readString(outside));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }
    }
}

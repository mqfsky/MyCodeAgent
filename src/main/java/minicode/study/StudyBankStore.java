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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores the current authoritative study bank at a host-controlled fixed path.
 *
 * <p>Writes use a uniquely named sibling file, force its contents to stable storage, and
 * atomically replace {@code bank.json}. Existing symbolic links in the controlled study path
 * are rejected instead of followed.</p>
 */
public final class StudyBankStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path home;
    private final Path studyDirectory;
    private final Path bankPath;

    public StudyBankStore(Path home) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.studyDirectory = this.home.resolve("study");
        this.bankPath = studyDirectory.resolve("bank.json");
    }

    /**
     * Reads the current bank without creating storage directories.
     *
     * @return the stored JSON object, or empty when no bank has been imported
     */
    public Optional<ObjectNode> read() {
        if (!verifyExistingStudyDirectory()) {
            return Optional.empty();
        }
        if (!Files.exists(bankPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        requireSafeRegularFile(bankPath, "study bank");
        try {
            JsonNode parsed = MAPPER.readTree(Files.readAllBytes(bankPath));
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalStateException("Study bank must contain one JSON object: " + bankPath);
            }
            return Optional.of(object.deepCopy());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid study bank JSON: " + bankPath, exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read study bank: " + bankPath, exception);
        }
    }

    /**
     * Atomically replaces the complete current bank.
     */
    public void write(ObjectNode bank) {
        ObjectNode snapshot = Objects.requireNonNull(bank, "bank").deepCopy();
        byte[] bytes;
        try {
            bytes = (MAPPER.writeValueAsString(snapshot) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize study bank", exception);
        }

        ensureStudyDirectory();
        rejectUnsafeExistingTarget(bankPath, "study bank");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(studyDirectory, ".bank.", ".tmp");
            rejectSymbolicLink(temporary, "temporary study bank");
            writeAndForce(temporary, bytes);
            rejectUnsafeExistingTarget(bankPath, "study bank");
            Files.move(
                    temporary,
                    bankPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            temporary = null;
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IllegalStateException(
                    "Atomic replacement is not supported for study bank: " + bankPath, exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to replace study bank: " + bankPath, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the authoritative write failure. A unique temp file is never a bank.
                }
            }
        }
    }

    public Path path() {
        return bankPath;
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

    private static void writeAndForce(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                target,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }
}

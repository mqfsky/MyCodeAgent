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
     * 不直接覆盖 bank.json，而是先完整写入临时文件，确认落盘后再原子替换，避免程序中断时留下半份题库。
     */
    public void write(ObjectNode bank) {
        // 先做防御性深拷贝，避免调用方在序列化期间继续修改传入的可变 JSON 节点。
        ObjectNode snapshot = Objects.requireNonNull(bank, "bank").deepCopy();
        byte[] bytes;
        try {
            // bank统一序列化成 UTF-8，并补一个换行，便于人工查看和命令行工具读取。
            bytes = (MAPPER.writeValueAsString(snapshot) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize study bank", exception);
        }

        // 真正写文件前先确保受控目录存在，并拒绝覆盖符号链接或非普通文件。
        ensureStudyDirectory();
        rejectUnsafeExistingTarget(bankPath, "study bank");

        // temporary 同时表示临时文件路径和清理责任；成功移动后会置空，避免 finally 再处理。
        Path temporary = null;
        try {
            // 创建临时文件，临时文件必须创建在题库同级目录，才能保证后面的原子移动发生在同一文件系统内。
            // 文件名类似 .bank.123456789.tmp
            temporary = Files.createTempFile(studyDirectory, ".bank.", ".tmp");
            rejectSymbolicLink(temporary, "temporary study bank");

            // 先把完整内容写入临时文件并强制刷盘，目标 bank.json 此时仍保持原样。
            writeAndForce(temporary, bytes);

            // 写临时文件期间目标可能被其他进程替换，因此移动前再次校验目标类型。
            rejectUnsafeExistingTarget(bankPath, "study bank");
            // 原子替换
            Files.move(
                    temporary,
                    bankPath,
                    StandardCopyOption.ATOMIC_MOVE, // 整个替换操作不可分割，读取者只会看到完整旧文件或完整新文件。
                    StandardCopyOption.REPLACE_EXISTING // 允许替换现有 bank.json
            );
            // 临时文件已经成为正式 bank.json，当前方法不再负责清理临时路径。
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
                    // 保留真正的写入异常；唯一命名的残留临时文件不会被当作正式题库读取。
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

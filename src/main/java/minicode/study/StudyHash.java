package minicode.study;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;

/** Study 领域中稳定标识和内容版本使用的哈希工具。 */
final class StudyHash {
    private StudyHash() {
    }

    static String questionId(String chapter, String question) {
        return "q_" + sha256(normalize(chapter) + "\0" + normalize(question)).substring(0, 32);
    }

    static String revisionHash(String chapter, String question, String answer) {
        return sha256(normalize(chapter) + "\0" + normalize(question) + "\0" + normalize(answer));
    }

    static String contentHash(String content) {
        return sha256(Objects.requireNonNull(content, "content").replace("\r\n", "\n").replace('\r', '\n'));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String normalize(String value) {
        return Normalizer.normalize(Objects.requireNonNull(value, "value"), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip();
    }
}

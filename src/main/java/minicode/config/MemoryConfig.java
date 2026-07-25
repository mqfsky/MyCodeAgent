package minicode.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 用户级个人记忆配置。
 *
 * <p>v1 只允许配置是否启用和用于解析日期的时区；其余限制固定在宿主中，
 * 避免项目配置扩大后台任务的权限或资源占用。</p>
 */
public record MemoryConfig(boolean enabled, ZoneId timezone) {
    public static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Asia/Shanghai");

    public static final int RECENT_TURN_COUNT = 5;
    public static final int EXTRACTION_MAX_STEPS = 6;
    public static final int SNAPSHOT_MAX_CHARS = 24_000;
    public static final int MESSAGE_MAX_CHARS = 8_000;
    public static final int FILE_MAX_BYTES = 128 * 1024;
    public static final int PROMPT_FILE_MAX_CHARS = 8_000;
    public static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    public MemoryConfig {
        timezone = Objects.requireNonNull(timezone, "timezone");
    }

    public static MemoryConfig disabled() {
        return new MemoryConfig(false, DEFAULT_TIMEZONE);
    }
}

package minicode.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 用户级个人记忆配置。
 *
 * <p>v1 只允许配置是否启用和用于解析日期的时区；其余限制固定在宿主中，
 * 避免项目配置扩大后台任务的权限或资源占用。</p>
 *
 * @param enabled 是否启用自动个人记忆提取
 * @param timezone 解析“今天”“明天”等相对日期时使用的时区
 */
public record MemoryConfig(boolean enabled, ZoneId timezone) {
    /** 未显式配置时使用的默认时区。 */
    public static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Asia/Shanghai");

    /** 一次提取任务最多保留的最近用户 Turn 数量。 */
    public static final int RECENT_TURN_COUNT = 5;
    /** 记忆提取 Agent 单次运行允许执行的最大模型步骤数。 */
    public static final int EXTRACTION_MAX_STEPS = 8;
    /** 一次对话快照允许包含的最大字符数。 */
    public static final int SNAPSHOT_MAX_CHARS = 24_000;
    /** 对话快照中单条消息允许包含的最大字符数。 */
    public static final int MESSAGE_MAX_CHARS = 8_000;
    /** 单个个人记忆文件允许占用的最大字节数。 */
    public static final int FILE_MAX_BYTES = 128 * 1024;
    /** 单个记忆文件注入主模型 Prompt 时允许包含的最大字符数。 */
    public static final int PROMPT_FILE_MAX_CHARS = 8_000;
    /** 应用关闭时等待后台记忆提取任务结束的最长时间。 */
    public static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    public MemoryConfig {
        timezone = Objects.requireNonNull(timezone, "timezone");
    }

    /** 创建一份关闭自动记忆、使用默认时区的配置。 */
    public static MemoryConfig disabled() {
        return new MemoryConfig(false, DEFAULT_TIMEZONE);
    }
}

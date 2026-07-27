package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

public final class JLineTerminalScreen implements TerminalScreen {
    private static final String HEADER_PREFIX = "  ◆ CodeAgent ";
    private static final String USER_PREFIX = "  ❯ ";
    private static final String USER_LABEL = "You";
    private static final String USER_LINE_PREFIX = USER_PREFIX + USER_LABEL + " › ";
    private static final AttributedStyle USER_LABEL_STYLE =
            AttributedStyle.DEFAULT.foreground(74, 222, 128).bold();
    private static final String ASSISTANT_PREFIX = "  ● ";
    private static final String ASSISTANT_LABEL = "CodeAgent";
    private static final String ASSISTANT_LINE_PREFIX = ASSISTANT_PREFIX + ASSISTANT_LABEL + " › ";
    private static final AttributedStyle ASSISTANT_LABEL_STYLE =
            AttributedStyle.DEFAULT.foreground(96, 165, 250).bold();
    private static final AttributedStyle BRAND_STYLE =
            AttributedStyle.DEFAULT.foreground(167, 139, 250).bold();
    private static final AttributedStyle TOOL_STYLE =
            AttributedStyle.DEFAULT.foreground(34, 211, 238);
    private static final AttributedStyle SUCCESS_STYLE =
            AttributedStyle.DEFAULT.foreground(74, 222, 128).bold();
    private static final AttributedStyle ERROR_STYLE =
            AttributedStyle.DEFAULT.foreground(248, 113, 113).bold();
    private static final AttributedStyle WARNING_STYLE =
            AttributedStyle.DEFAULT.foreground(251, 191, 36).bold();
    private static final AttributedStyle MUTED_STYLE =
            AttributedStyle.DEFAULT.foreground(113, 121, 136);
    private static final AttributedStyle INPUT_STYLE =
            AttributedStyle.DEFAULT.foreground(167, 139, 250).bold();
    private static final String NORMAL_PLACEHOLDER = "Ask CodeAgent to build, explain, or fix something…";
    private static final String ANSWER_PLACEHOLDER = "Type your answer…";
    private static final String PERMISSION_PLACEHOLDER = "Choose an option…";
    private static final String FEEDBACK_PLACEHOLDER = "Add a reason…";
    private static final String ENTER_ALTERNATE_SCREEN = "\u001B[?1049h";
    private static final String EXIT_ALTERNATE_SCREEN = "\u001B[?1049l";
    private static final String ENABLE_ALTERNATE_SCROLL = "\u001B[?1007h";
    private static final String DISABLE_ALTERNATE_SCROLL = "\u001B[?1007l";
    private static final String ENABLE_MOUSE_TRACKING = "\u001B[?1000h";
    private static final String ENABLE_SGR_MOUSE = "\u001B[?1006h";
    private static final String DISABLE_ALL_MOUSE_MODES = "\u001B[?1000l"
            + "\u001B[?1002l"
            + "\u001B[?1003l"
            + "\u001B[?1005l"
            + "\u001B[?1006l"
            + "\u001B[?1015l";
    private static final String CURSOR_HOME = "\u001B[H";
    private static final String CLEAR_SCREEN = "\u001B[2J";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    private final Terminal terminal;
    private final PrintWriter writer;
    private final Attributes originalAttributes;
    private final IntConsumer exitHandler;
    private final Terminal.SignalHandler previousInterruptHandler;
    private final Thread shutdownHook;
    private final boolean shutdownHookRegistered;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean interruptHandlerRestored = new AtomicBoolean(false);

    public JLineTerminalScreen(Terminal terminal) {
        this(terminal, System::exit);
    }

    JLineTerminalScreen(Terminal terminal, IntConsumer exitHandler) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.writer = terminal.writer();
        // JLineTuiInput 会在 Screen 创建后进入 raw mode；这里提前保存原始属性，确保退出时能完整恢复。
        this.originalAttributes = new Attributes(terminal.getAttributes());
        this.exitHandler = Objects.requireNonNull(exitHandler, "exitHandler");
        this.shutdownHook = new Thread(this::restoreOnShutdown, "codeagent-terminal-restore");
        // 不依赖 JVM shutdown hook 的执行顺序：Ctrl+C 到达时先同步恢复终端，再结束进程。
        this.previousInterruptHandler = terminal.handle(Terminal.Signal.INT, this::handleInterrupt);
        this.shutdownHookRegistered = registerShutdownHook(shutdownHook);
        writer.print(ENTER_ALTERNATE_SCREEN);
        writer.print(ENABLE_ALTERNATE_SCROLL);
        writer.print(ENABLE_MOUSE_TRACKING);
        writer.print(ENABLE_SGR_MOUSE);
        writer.print(SHOW_CURSOR);
        writer.print(CLEAR_SCREEN);
        writer.print(CURSOR_HOME);
        writer.flush();
    }

    @Override
    public TerminalSize size() {
        return new TerminalSize(Math.max(1, terminal.getWidth()), Math.max(1, terminal.getHeight()));
    }

    @Override
    public void redraw(RenderFrame frame) {
        Objects.requireNonNull(frame, "frame");
        writer.print(SHOW_CURSOR);
        writer.print(CURSOR_HOME);
        writer.print(CLEAR_SCREEN);
        writer.print(CURSOR_HOME);
        StringJoiner rendered = new StringJoiner("\r\n");
        for (String line : frame.lines()) {
            rendered.add(styleLine(line).toAnsi(terminal));
        }
        writer.print(rendered);
        if (frame.cursorRow() > 0 && frame.cursorColumn() > 0) {
            writer.print("\u001B[" + frame.cursorRow() + ";" + frame.cursorColumn() + "H");
        }
        writer.flush();
    }

    /**
     * 按渲染器输出的语义前缀应用终端主题；返回值的可见文本与原始行完全一致。
     */
    static AttributedString styleLine(String line) {
        String value = Objects.requireNonNull(line, "line");
        if (value.startsWith(HEADER_PREFIX)) {
            return styleHeader(value);
        }
        if (value.startsWith(USER_LINE_PREFIX)) {
            return stylePrefix(value, USER_LINE_PREFIX, USER_LABEL_STYLE);
        }
        if (value.startsWith(ASSISTANT_LINE_PREFIX)) {
            return stylePrefix(value, ASSISTANT_LINE_PREFIX, ASSISTANT_LABEL_STYLE);
        }
        if (value.startsWith("  ❯ Answer › ")) {
            return stylePrefix(value, "  ❯ Answer › ", USER_LABEL_STYLE);
        }
        if (value.startsWith("  ◇ ")) {
            return styleToolHeading(value);
        }
        if (value.startsWith("    │ ")) {
            return stylePrefix(value, "    │ ", MUTED_STYLE);
        }
        if (value.startsWith("  ? Question › ")) {
            return stylePrefix(value, "  ? Question › ", WARNING_STYLE);
        }
        if (value.startsWith("  ! Permission › ")) {
            return stylePrefix(value, "  ! Permission › ", WARNING_STYLE);
        }
        if (value.startsWith("  ↻ Context › ")) {
            return stylePrefix(value, "  ↻ Context › ", BRAND_STYLE);
        }
        if (value.startsWith("  ◎ Agent task › ")) {
            return stylePrefix(value, "  ◎ Agent task › ", TOOL_STYLE);
        }
        if (value.startsWith("  · ")) {
            return styleWholeLine(value, MUTED_STYLE);
        }
        if (isHorizontalRule(value)) {
            return styleWholeLine(value, MUTED_STYLE);
        }
        if (value.startsWith("  ● ")) {
            return styleStatus(value);
        }
        for (String prompt : new String[]{"  › ", "  … ", "  answer › ", "  allow › ", "  feedback › "}) {
            if (value.startsWith(prompt)) {
                return styleInput(value, prompt);
            }
        }
        return new AttributedString(value);
    }

    private static AttributedString styleHeader(String line) {
        int dividerStart = line.indexOf('─');
        int titleEnd = dividerStart < 0 ? Math.min(line.length(), HEADER_PREFIX.length()) : dividerStart;
        AttributedStringBuilder builder = new AttributedStringBuilder(line.length())
                .styled(BRAND_STYLE, line.substring(0, titleEnd));
        if (titleEnd < line.length()) {
            builder.styled(MUTED_STYLE, line.substring(titleEnd));
        }
        return builder.toAttributedString();
    }

    private static AttributedString styleToolHeading(String line) {
        int contentEnd = line.stripTrailing().length();
        if (contentEnd == 0) {
            return new AttributedString(line);
        }
        int statusStart = contentEnd - 1;
        char status = line.charAt(statusStart);
        AttributedStyle statusStyle = switch (status) {
            case '✓' -> SUCCESS_STYLE;
            case '✗' -> ERROR_STYLE;
            default -> TOOL_STYLE;
        };
        AttributedStringBuilder builder = new AttributedStringBuilder(line.length());
        if (status == '✓' || status == '✗' || status == '…') {
            builder.styled(TOOL_STYLE, line.substring(0, statusStart))
                    .styled(statusStyle, line.substring(statusStart, contentEnd));
        } else {
            builder.styled(TOOL_STYLE, line.substring(0, contentEnd));
        }
        if (contentEnd < line.length()) {
            builder.append(line, contentEnd, line.length());
        }
        return builder.toAttributedString();
    }

    private static AttributedString styleStatus(String line) {
        AttributedStyle style = line.startsWith("  ● Ready") ? SUCCESS_STYLE
                : line.contains("approval") || line.contains("answer") ? WARNING_STYLE
                : BRAND_STYLE;
        int statusEnd = line.indexOf("  ·  ");
        if (statusEnd < 0) {
            statusEnd = line.stripTrailing().length();
        }
        AttributedStringBuilder builder = new AttributedStringBuilder(line.length())
                .styled(style, line.substring(0, statusEnd));
        if (statusEnd < line.length()) {
            builder.styled(MUTED_STYLE, line.substring(statusEnd));
        }
        return builder.toAttributedString();
    }

    private static AttributedString styleInput(String line, String prompt) {
        int placeholderEnd = placeholderEnd(line, prompt);
        AttributedStringBuilder builder = new AttributedStringBuilder(line.length())
                .styled(INPUT_STYLE, prompt);
        if (placeholderEnd > prompt.length()) {
            builder.styled(MUTED_STYLE, line.substring(prompt.length(), placeholderEnd));
            builder.append(line, placeholderEnd, line.length());
        } else {
            builder.append(line, prompt.length(), line.length());
        }
        return builder.toAttributedString();
    }

    private static int placeholderEnd(String line, String prompt) {
        for (String placeholder : new String[]{
                NORMAL_PLACEHOLDER, ANSWER_PLACEHOLDER, PERMISSION_PLACEHOLDER, FEEDBACK_PLACEHOLDER
        }) {
            if (line.startsWith(prompt + placeholder)) {
                return prompt.length() + placeholder.length();
            }
        }
        return prompt.length();
    }

    private static boolean isHorizontalRule(String line) {
        String stripped = line.strip();
        return !stripped.isEmpty() && stripped.codePoints().allMatch(codePoint -> codePoint == '─');
    }

    private static AttributedString stylePrefix(String line, String prefix, AttributedStyle style) {
        return new AttributedStringBuilder(line.length())
                .styled(style, prefix)
                .append(line, prefix.length(), line.length())
                .toAttributedString();
    }

    private static AttributedString styleWholeLine(String line, AttributedStyle style) {
        return new AttributedStringBuilder(line.length())
                .styled(style, line)
                .toAttributedString();
    }

    @Override
    public void close() {
        restoreTerminal();
        restoreInterruptHandler();
        removeShutdownHook();
    }

    private void handleInterrupt(Terminal.Signal signal) {
        restoreTerminal();
        // 128 + SIGINT(2) 是命令行程序被 Ctrl+C 终止时的标准退出码。
        exitHandler.accept(130);
    }

    /**
     * JVM 正常关闭、收到 Ctrl+C 或执行 System.exit 时使用的兜底清理入口。
     */
    void restoreOnShutdown() {
        restoreTerminal();
    }

    private void restoreTerminal() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // 有些终端会在退出 alternate screen 时恢复私有模式，因此切屏前后各关闭一次鼠标追踪。
        writer.print(DISABLE_ALL_MOUSE_MODES);
        writer.print(DISABLE_ALTERNATE_SCROLL);
        writer.print(SHOW_CURSOR);
        writer.print(EXIT_ALTERNATE_SCREEN);
        writer.print(DISABLE_ALL_MOUSE_MODES);
        writer.print(DISABLE_ALTERNATE_SCROLL);
        writer.print(SHOW_CURSOR);
        writer.flush();

        // 恢复 ICANON、ECHO 等进入 TUI 前的终端属性，避免异常退出后 shell 仍停留在 raw mode。
        try {
            terminal.setAttributes(new Attributes(originalAttributes));
        } catch (RuntimeException ignored) {
            // 终端可能已经在关闭；控制序列已经尽力输出，属性恢复也保持 best-effort。
        }
    }

    private static boolean registerShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return true;
        } catch (IllegalStateException | SecurityException ignored) {
            return false;
        }
    }

    private void removeShutdownHook() {
        if (!shutdownHookRegistered) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException | SecurityException ignored) {
            // JVM 已经进入 shutdown 阶段时不能移除 hook；hook 内的原子关闭保证重复调用安全。
        }
    }

    private void restoreInterruptHandler() {
        if (!interruptHandlerRestored.compareAndSet(false, true)) {
            return;
        }
        try {
            terminal.handle(Terminal.Signal.INT, previousInterruptHandler);
        } catch (RuntimeException ignored) {
            // 终端可能已经关闭；进程退出路径无需再恢复旧 handler。
        }
    }
}

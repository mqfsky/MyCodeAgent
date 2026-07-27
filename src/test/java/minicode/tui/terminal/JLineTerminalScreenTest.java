package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JLineTerminalScreenTest {
    @Test
    void userRolePrefixIsGreenWithoutColoringBodyMentions() {
        String line = "  ❯ You › You asked a question";

        AttributedString styled = JLineTerminalScreen.styleLine(line);

        assertEquals(line, styled.toString());
        AttributedStyle green = AttributedStyle.DEFAULT.foreground(74, 222, 128).bold();
        int bodyStart = "  ❯ You › ".length();
        for (int index = 0; index < bodyStart; index++) {
            assertEquals(green, styled.styleAt(index));
        }
        assertEquals(AttributedStyle.DEFAULT, styled.styleAt(bodyStart));
        assertEquals(AttributedStyle.DEFAULT, styled.styleAt(line.lastIndexOf("You")));
    }

    @Test
    void assistantRolePrefixIsBlueWithoutColoringBodyMentions() {
        String line = "  ● CodeAgent › CodeAgent can help";

        AttributedString styled = JLineTerminalScreen.styleLine(line);

        assertEquals(line, styled.toString());
        AttributedStyle blue = AttributedStyle.DEFAULT.foreground(96, 165, 250).bold();
        int bodyStart = "  ● CodeAgent › ".length();
        for (int index = 0; index < bodyStart; index++) {
            assertEquals(blue, styled.styleAt(index));
        }
        assertEquals(AttributedStyle.DEFAULT, styled.styleAt(bodyStart));
        assertEquals(AttributedStyle.DEFAULT, styled.styleAt(line.lastIndexOf("CodeAgent")));
    }

    @Test
    void ordinaryLinesKeepDefaultStyle() {
        String line = "ordinary CodeAgent text";

        AttributedString styled = JLineTerminalScreen.styleLine(line);

        assertEquals(line, styled.toString());
        for (int index = 0; index < line.length(); index++) {
            assertEquals(AttributedStyle.DEFAULT, styled.styleAt(index));
        }
    }

    @Test
    void chromeToolsAndPromptsUseSemanticThemeColorsWithoutChangingText() {
        AttributedStyle brand = AttributedStyle.DEFAULT.foreground(167, 139, 250).bold();
        AttributedStyle tool = AttributedStyle.DEFAULT.foreground(34, 211, 238);
        AttributedStyle success = AttributedStyle.DEFAULT.foreground(74, 222, 128).bold();
        AttributedStyle error = AttributedStyle.DEFAULT.foreground(248, 113, 113).bold();
        AttributedStyle warning = AttributedStyle.DEFAULT.foreground(251, 191, 36).bold();
        AttributedStyle muted = AttributedStyle.DEFAULT.foreground(113, 121, 136);

        String header = "  ◆ CodeAgent ───── ↑↓ history  ";
        AttributedString styledHeader = JLineTerminalScreen.styleLine(header);
        assertEquals(header, styledHeader.toString());
        assertEquals(brand, styledHeader.styleAt(header.indexOf("CodeAgent")));
        assertEquals(muted, styledHeader.styleAt(header.indexOf('─')));

        String successfulTool = "  ◇ read_file  ✓   ";
        AttributedString styledSuccess = JLineTerminalScreen.styleLine(successfulTool);
        assertEquals(successfulTool, styledSuccess.toString());
        assertEquals(tool, styledSuccess.styleAt(successfulTool.indexOf("read_file")));
        assertEquals(success, styledSuccess.styleAt(successfulTool.indexOf('✓')));

        String failedTool = "  ◇ run_command  ✗";
        AttributedString styledError = JLineTerminalScreen.styleLine(failedTool);
        assertEquals(failedTool, styledError.toString());
        assertEquals(error, styledError.styleAt(failedTool.indexOf('✗')));

        String permission = "  ! Permission › pending";
        AttributedString styledPermission = JLineTerminalScreen.styleLine(permission);
        assertEquals(permission, styledPermission.toString());
        assertEquals(warning, styledPermission.styleAt(permission.indexOf('!')));

        String input = "  › Ask CodeAgent to build, explain, or fix something…  ";
        AttributedString styledInput = JLineTerminalScreen.styleLine(input);
        assertEquals(input, styledInput.toString());
        assertEquals(brand, styledInput.styleAt(input.indexOf('›')));
        assertEquals(muted, styledInput.styleAt(input.indexOf("Ask")));
    }

    @Test
    void screenUsesAlternateScreenAndClearsForRedraws() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(output);

        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        screen.redraw(new RenderFrame(8, 3, List.of(
                "one     ",
                "two     ",
                ">       "
        )));
        screen.close();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[?1049h") || text.contains("\u001B[?47h"), text);
        assertTrue(text.contains("\u001B[H") || text.contains("\u001B[1;1H"), text);
        assertTrue(text.contains("\u001B[2J") || text.contains("\u001B[J"), text);
        assertTrue(text.contains("\u001B[?1049l") || text.contains("\u001B[?47l"), text);
    }

    @Test
    void screenEnablesAndRestoresAlternateScrollAndSgrMouseTracking() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(output);

        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        screen.close();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[?1007h"), text);
        assertTrue(text.contains("\u001B[?1007l"), text);
        assertTrue(text.contains("\u001B[?1000h"), text);
        assertTrue(text.contains("\u001B[?1000l"), text);
        assertTrue(text.contains("\u001B[?1006h"), text);
        assertTrue(text.contains("\u001B[?1006l"), text);
        assertTrue(text.contains("\u001B[?1002l"), text);
        assertTrue(text.contains("\u001B[?1003l"), text);
        assertTrue(text.contains("\u001B[?1005l"), text);
        assertTrue(text.contains("\u001B[?1015l"), text);
        int alternateScreenExit = text.indexOf("\u001B[?1049l");
        assertTrue(text.indexOf("\u001B[?1000l") < alternateScreenExit, text);
        assertTrue(text.lastIndexOf("\u001B[?1000l") > alternateScreenExit, text);
    }

    @Test
    void closeRestoresTerminalAttributesCapturedBeforeRawMode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(output);
        setCookedMode(terminal);
        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);

        terminal.enterRawMode();
        assertFalse(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
        assertFalse(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO));

        screen.close();

        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO));
    }

    @Test
    void shutdownCleanupRestoresMouseModesAndTerminalAttributes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(output);
        setCookedMode(terminal);
        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        terminal.enterRawMode();

        screen.restoreOnShutdown();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[?1000l"), text);
        assertTrue(text.contains("\u001B[?1006l"), text);
        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO));
        // 移除测试进程中注册的 shutdown hook；restoreTerminal 本身保持幂等。
        screen.close();
    }

    @Test
    void interruptSignalRestoresTerminalBeforeRequestingExit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(output);
        setCookedMode(terminal);
        AtomicInteger exitCode = new AtomicInteger(-1);
        AtomicBoolean cleanupVisibleAtExit = new AtomicBoolean(false);
        JLineTerminalScreen screen = new JLineTerminalScreen(terminal, code -> {
            exitCode.set(code);
            String text = output.toString(StandardCharsets.UTF_8);
            cleanupVisibleAtExit.set(text.contains("\u001B[?1000l")
                    && text.contains("\u001B[?1006l")
                    && text.contains("\u001B[?1049l"));
        });
        terminal.enterRawMode();

        terminal.raise(Terminal.Signal.INT);

        assertEquals(130, exitCode.get());
        assertTrue(cleanupVisibleAtExit.get());
        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO));
        screen.close();
    }

    @Test
    void closeIsIdempotent() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JLineTerminalScreen screen = new JLineTerminalScreen(terminal(output));

        screen.close();
        screen.close();

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(2, countOccurrences(text, "\u001B[?1000l"), text);
        assertEquals(1, countOccurrences(text, "\u001B[?1049l"), text);
    }

    @Test
    void redrawShowsAndPositionsRealTerminalCursor() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(output);

        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        screen.redraw(new RenderFrame(20, 3, List.of(
                "CodeAgent           ",
                "Ready               ",
                "mini-code>          "
        ), 3, 12));
        screen.close();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[?25h"), text);
        assertTrue(text.contains("\u001B[3;12H"), text);
    }

    @Test
    void redrawAddsAnsiColorWithoutChangingVisibleFrameText() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(output);
        String assistant = "  ● CodeAgent › hello CodeAgent";
        String ansiAssistant = JLineTerminalScreen.styleLine(assistant).toAnsi(terminal);

        assertTrue(ansiAssistant.contains("\u001B["), ansiAssistant);
        assertEquals(assistant, AttributedString.stripAnsi(ansiAssistant));

        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        screen.redraw(new RenderFrame(32, 3, List.of(
                assistant + " ",
                "  ● Ready" + " ".repeat(23),
                "  › " + " ".repeat(28)
        ), 3, 5));
        screen.close();

        String outputText = output.toString(StandardCharsets.UTF_8);
        String visibleText = AttributedString.stripAnsi(outputText);
        assertTrue(visibleText.contains(assistant), visibleText);
    }

    private static Terminal terminal(ByteArrayOutputStream output) throws Exception {
        return new DumbTerminal(
                "test-terminal",
                "xterm",
                new ByteArrayInputStream(new byte[0]),
                output,
                StandardCharsets.UTF_8
        );
    }

    private static void setCookedMode(Terminal terminal) {
        Attributes attributes = terminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, true);
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, true);
        terminal.setAttributes(attributes);
    }

    private static int countOccurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}

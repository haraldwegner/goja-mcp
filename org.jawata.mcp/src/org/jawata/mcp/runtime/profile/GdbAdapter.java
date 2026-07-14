package org.jawata.mcp.runtime.profile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 24 (D14) — an OPTIONAL external adapter for native-crash triage
 * beyond what {@link HsErrParser} already gets for free from the JVM's own
 * report. Deliberately split in two: {@link #isAvailable} / {@link #runBacktrace}
 * (thin, genuinely needs the external tool, hard to unit-test without it) and
 * {@link #parseBacktrace} (a pure function over gdb's own text output,
 * unit-testable with a fixture transcript whether or not gdb is installed on
 * the box running the test — gdb's {@code thread apply all bt} frame syntax
 * is a decades-stable, documented format).
 *
 * <p>Honest about platform support (spec D14): if no adapter command is
 * configured or resolvable on {@code PATH}, {@link #isAvailable} says so and
 * {@code profile(action=native_handoff)} reports a capability-absent result —
 * never a silent skip.</p>
 */
public final class GdbAdapter {

    private static final Pattern THREAD_HEADER = Pattern.compile("^Thread (?<num>\\d+) \\(.*\\):\\s*$");
    // A SEPARATE find() against the header line, not folded into THREAD_HEADER: a lazy
    // `.*?` either side of an OPTIONAL "LWP N" group lets the engine satisfy the whole
    // match by skipping the group entirely (the trailing `.*?` happily swallows the
    // literal "(LWP 5002)" text as ordinary characters) — found live against a real
    // fixture transcript, Sprint 24 Stage 19: `lwp` came back null every time.
    private static final Pattern LWP_IN_HEADER = Pattern.compile("LWP (?<lwp>\\d+)");
    // A frame with debug info ends " at file:line"; one resolved only via the dynamic
    // symbol table (routine system-library calls — libc, libpthread — almost never carry
    // debug info) ends " from /path/to/lib.so" instead. Found live against a real gdb-shaped
    // fixture transcript, Sprint 24 Stage 19: every "from"-shaped frame matched neither
    // trailing group, so `matches()` failed and the frame was silently dropped — 3 of a
    // 5-frame transcript vanished with no error.
    private static final Pattern FRAME_LINE = Pattern.compile(
        "^#(?<num>\\d+)\\s+0x(?<addr>[0-9a-f]+) in (?<func>\\S+)\\s*\\((?<args>.*?)\\)"
            + "(?:\\s+at (?<file>[^:]+):(?<line>\\d+))?"
            + "(?:\\s+from (?<library>\\S+))?"
            + "\\s*$");

    private GdbAdapter() {
    }

    /** Is {@code command} (e.g. "gdb", "lldb") resolvable and runnable on THIS machine? */
    public static boolean isAvailable(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Run {@code <command> -batch -ex "thread apply all bt" <binary> <core>}, return its raw text. */
    public static String runBacktrace(String command, Path javaBinary, Path coreFile, int timeoutSeconds)
            throws Exception {
        List<String> full = List.of(command, "-batch", "-ex", "thread apply all bt",
            javaBinary.toString(), coreFile.toString());
        Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(command + " timed out after " + timeoutSeconds + "s");
        }
        return output;
    }

    /**
     * Parse a {@code thread apply all bt} transcript into per-thread frame lists.
     * Pure text-in, structure-out — no process, no filesystem; safe to unit-test
     * with a hand-authored transcript against gdb's own documented, stable format.
     */
    public static List<Map<String, Object>> parseBacktrace(String gdbOutput) {
        List<Map<String, Object>> threads = new ArrayList<>();
        Map<String, Object> currentThread = null;
        List<Map<String, Object>> currentFrames = null;

        for (String line : gdbOutput.split("\n")) {
            String stripped = line.strip();
            Matcher threadHeader = THREAD_HEADER.matcher(stripped);
            if (threadHeader.matches()) {
                currentThread = new LinkedHashMap<>();
                currentThread.put("threadNum", Integer.parseInt(threadHeader.group("num")));
                Matcher lwp = LWP_IN_HEADER.matcher(stripped);
                if (lwp.find()) {
                    currentThread.put("lwp", Integer.parseInt(lwp.group("lwp")));
                }
                currentFrames = new ArrayList<>();
                currentThread.put("frames", currentFrames);
                threads.add(currentThread);
                continue;
            }
            Matcher frame = FRAME_LINE.matcher(stripped);
            if (frame.matches() && currentFrames != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("frameNum", Integer.parseInt(frame.group("num")));
                f.put("address", "0x" + frame.group("addr"));
                f.put("function", frame.group("func"));
                f.put("args", frame.group("args"));
                if (frame.group("file") != null) {
                    f.put("file", frame.group("file"));
                    f.put("line", Integer.parseInt(frame.group("line")));
                } else if (frame.group("library") != null) {
                    f.put("library", frame.group("library"));
                }
                currentFrames.add(f);
            }
        }
        return threads;
    }
}

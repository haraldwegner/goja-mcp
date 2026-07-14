package org.jawata.mcp.runtime.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 24 (D14) — parses a HotSpot {@code hs_err_pid<pid>.log} fatal-error
 * report (the file the JVM itself writes when it crashes) into structured
 * evidence. HotSpot ALREADY correlates Java and native frames for the
 * crashing thread's own stack: the "Native frames:" section interleaves
 * {@code C}/{@code V}/{@code v} native frames with {@code J}/{@code j} Java
 * frames, and Java frames carry a fully resolved method signature — no
 * external debugger is needed to get that correlation, the JDK does it
 * itself. A configured gdb/lldb adapter ({@link GdbAdapter}) is an OPTIONAL
 * step beyond this baseline, for what this built-in capture does not cover
 * (a full post-mortem core dump, deeper native-only stacks).
 *
 * <p>Grounded against two REAL generated crashes (Sprint 24 Stage 19): a
 * {@code kill -SEGV} on an idle JVM (the degenerate case — "Current thread is
 * native thread", no Java frames section at all) and a genuine in-process
 * {@code Unsafe} out-of-bounds write (a real {@code JavaThread}, a resolved
 * native symbol {@code Unsafe_PutInt+0xa4} in {@code libjvm.so}, and the
 * crashing method's own name in the Java frames). Both shapes are handled
 * honestly rather than assumed from documentation alone.</p>
 */
public final class HsErrParser {

    private static final Pattern SIGNAL_LINE = Pattern.compile(
        "^#\\s+(?<signal>SIG\\w+)\\s+\\(0x[0-9a-f]+\\)\\s+at pc=0x[0-9a-f]+.*?pid=(?<pid>\\d+)",
        Pattern.MULTILINE);
    private static final Pattern JRE_VERSION = Pattern.compile("^# JRE version: (?<v>.+)$", Pattern.MULTILINE);
    private static final Pattern VM_VERSION = Pattern.compile("^# Java VM: (?<v>.+)$", Pattern.MULTILINE);
    private static final Pattern HOST_LINE = Pattern.compile("^Host:\\s+(?<host>.+)$", Pattern.MULTILINE);
    private static final Pattern CURRENT_THREAD_JAVA = Pattern.compile(
        "Current thread \\(0x[0-9a-f]+\\):\\s+JavaThread \"(?<name>[^\"]+)\"\\s+\\[(?<state>[^,\\]]+)");
    private static final Pattern HOST_MEMORY = Pattern.compile(
        "Memory:.*?physical (?<physTotal>\\d+)k\\((?<physFree>\\d+)k free\\), "
            + "swap (?<swapTotal>\\d+)k\\((?<swapFree>\\d+)k free\\)");
    private static final Pattern CORE_DUMP_PATH = Pattern.compile(
        "dumping to (?<path>[^)]+\\.\\S*\\d+)");

    // Bracketed native/VM frame: "[libjvm.so+0xfc8ac4]  Unsafe_PutInt+0xa4" or "[libc.so.6+0x91115]"
    private static final Pattern BRACKETED_NATIVE = Pattern.compile(
        "\\[(?<lib>[^+\\]]+)\\+(?<offset>0x[0-9a-f]+)\\](?:\\s{2,}(?<symbol>\\S.*))?");
    // Stub frame: "~StubRoutines::call_stub 0x0000744b23c5fcc6"
    private static final Pattern STUB_NATIVE = Pattern.compile(
        "~(?<stub>\\S+)\\s+(?<address>0x[0-9a-f]+)");
    // Java frame: "jdk.internal.misc.Unsafe.putInt(Ljava/lang/Object;JI)V+0 java.base@21.0.10"
    private static final Pattern JAVA_FRAME = Pattern.compile(
        "(?<qualified>[\\w.$]+)\\((?<descriptor>[^)]*)\\)\\S*(?:\\s+(?<module>\\S+@\\S+))?");

    private HsErrParser() {
    }

    public static Map<String, Object> parse(Path hsErrFile) throws IOException {
        String raw = Files.readString(hsErrFile);
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("file", hsErrFile.toString());
        putIfMatched(result, "signal", SIGNAL_LINE, raw, "signal");
        putIfMatched(result, "jreVersion", JRE_VERSION, raw, "v");
        putIfMatched(result, "vmVersion", VM_VERSION, raw, "v");
        putIfMatched(result, "host", HOST_LINE, raw, "host");
        result.put("problematicFrame", firstLineAfter(raw, "# Problematic frame:"));

        Matcher threadHeader = CURRENT_THREAD_JAVA.matcher(raw);
        Map<String, Object> thread = new LinkedHashMap<>();
        if (threadHeader.find()) {
            thread.put("javaThread", true);
            thread.put("name", threadHeader.group("name"));
            thread.put("state", threadHeader.group("state"));
        } else {
            thread.put("javaThread", false);
            thread.put("why", "the crashing thread carried no attached Java frame — a native or "
                + "VM-internal thread at the moment of the signal, not a JavaThread running "
                + "bytecode");
        }
        result.put("crashingThread", thread);

        String nativeSection = sectionBetween(raw, "Native frames:", List.of("Java frames:", ""));
        List<Map<String, Object>> nativeFrames = parseFrames(nativeSection);
        result.put("nativeFrames", nativeFrames);

        List<Map<String, Object>> javaFrames = new ArrayList<>();
        for (Map<String, Object> frame : nativeFrames) {
            if (Boolean.TRUE.equals(frame.get("isJava"))) {
                javaFrames.add(frame);
            }
        }
        result.put("javaFrames", javaFrames);

        Matcher mem = HOST_MEMORY.matcher(raw);
        if (mem.find()) {
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("physicalTotalKb", Long.parseLong(mem.group("physTotal")));
            memory.put("physicalFreeKb", Long.parseLong(mem.group("physFree")));
            memory.put("swapTotalKb", Long.parseLong(mem.group("swapTotal")));
            memory.put("swapFreeKb", Long.parseLong(mem.group("swapFree")));
            result.put("hostMemory", memory);
        }

        if (raw.contains("Native Memory Tracking:")) {
            // NOT the first blank line: HotSpot inserts a "(Omitting categories weighting
            // less than 1KB)" NOTE followed by its OWN blank line before the real "Total:"
            // + category breakdown even starts (grounded against a real generated crash,
            // Sprint 24 Stage 19 — the naive first-blank-line cut lost every category).
            // The section's true end is the next major "---------------" divider.
            String nmtSection = sectionBetween(raw, "Native Memory Tracking:", List.of("---------------"));
            result.put("nativeMemoryTracking", ProfileParsers.parseNativeMemory(nmtSection));
        } else {
            result.put("nativeMemoryTracking", Map.of(
                "enabled", false,
                "why", "this crash's JVM was not started with -XX:NativeMemoryTracking=summary "
                    + "(or =detail) — cannot be enabled after the fact, only at the ORIGINAL launch."));
        }

        Matcher core = CORE_DUMP_PATH.matcher(raw);
        if (core.find()) {
            result.put("coreDumpPath", core.group("path").strip());
        }

        return result;
    }

    private static List<Map<String, Object>> parseFrames(String section) {
        List<Map<String, Object>> frames = new ArrayList<>();
        if (section == null) {
            return frames;
        }
        for (String line : section.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("Native frames:") || trimmed.startsWith("Java frames:")) {
                continue;
            }
            char kindChar = trimmed.charAt(0);
            String rest = trimmed.length() > 1 ? trimmed.substring(1).strip() : "";
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("raw", trimmed);
            switch (kindChar) {
                case 'J' -> { frame.put("kind", "compiledJava"); frame.put("isJava", true); parseJavaFrame(rest, frame); }
                case 'j' -> { frame.put("kind", "interpretedJava"); frame.put("isJava", true); parseJavaFrame(rest, frame); }
                case 'V' -> { frame.put("kind", "vmInternal"); frame.put("isJava", false); parseNativeFrame(rest, frame); }
                case 'v' -> { frame.put("kind", "vmStub"); frame.put("isJava", false); parseNativeFrame(rest, frame); }
                case 'C' -> { frame.put("kind", "native"); frame.put("isJava", false); parseNativeFrame(rest, frame); }
                default -> { frame.put("kind", "unknown"); frame.put("isJava", false); }
            }
            frames.add(frame);
        }
        return frames;
    }

    private static void parseJavaFrame(String rest, Map<String, Object> frame) {
        Matcher m = JAVA_FRAME.matcher(rest);
        if (!m.find()) {
            return;
        }
        String qualified = m.group("qualified");
        int lastDot = qualified.lastIndexOf('.');
        if (lastDot > 0) {
            frame.put("symbol", qualified.substring(0, lastDot) + "#" + qualified.substring(lastDot + 1));
        } else {
            frame.put("symbol", qualified);
        }
        if (m.group("module") != null) {
            frame.put("module", m.group("module"));
        }
    }

    private static void parseNativeFrame(String rest, Map<String, Object> frame) {
        Matcher bracketed = BRACKETED_NATIVE.matcher(rest);
        if (bracketed.find()) {
            frame.put("library", bracketed.group("lib"));
            frame.put("offset", bracketed.group("offset"));
            if (bracketed.group("symbol") != null) {
                frame.put("resolvedSymbol", bracketed.group("symbol").strip());
            }
            return;
        }
        Matcher stub = STUB_NATIVE.matcher(rest);
        if (stub.find()) {
            frame.put("resolvedSymbol", stub.group("stub"));
            frame.put("address", stub.group("address"));
        }
        // Neither shape: the raw line is still there for a human to read; never invented.
    }

    private static void putIfMatched(Map<String, Object> result, String key, Pattern pattern,
                                     String raw, String group) {
        Matcher m = pattern.matcher(raw);
        if (m.find()) {
            result.put(key, m.group(group));
        }
    }

    private static String firstLineAfter(String raw, String marker) {
        int idx = raw.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int lineStart = raw.indexOf('\n', idx) + 1;
        if (lineStart <= 0 || lineStart >= raw.length()) {
            return null;
        }
        int lineEnd = raw.indexOf('\n', lineStart);
        String line = lineEnd < 0 ? raw.substring(lineStart) : raw.substring(lineStart, lineEnd);
        return line.replaceFirst("^#\\s*", "").strip();
    }

    /** Text strictly between {@code startMarker} and whichever of {@code endMarkers} comes first. */
    private static String sectionBetween(String raw, String startMarker, List<String> endMarkers) {
        int start = raw.indexOf(startMarker);
        if (start < 0) {
            return null;
        }
        int contentStart = raw.indexOf('\n', start) + 1;
        if (contentStart <= 0) {
            return null;
        }
        int end = raw.length();
        for (String marker : endMarkers) {
            int candidate = marker.isEmpty()
                ? raw.indexOf("\n\n", contentStart)
                : raw.indexOf(marker, contentStart);
            if (candidate >= 0 && candidate < end) {
                end = candidate;
            }
        }
        return raw.substring(contentStart, Math.min(end, raw.length()));
    }
}

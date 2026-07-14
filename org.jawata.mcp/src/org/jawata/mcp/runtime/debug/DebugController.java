package org.jawata.mcp.runtime.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.event.WatchpointEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.StepRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sprint 24 (D6) — the interactive debugger for one session: breakpoints, the
 * event pump that services them, stepping, snapshots, and evaluation.
 *
 * <p><b>The event pump is the heart.</b> A JDI connection delivers events on a
 * queue that <i>somebody must drain</i> — an undrained queue is a target that
 * eventually stalls. So one thread owns the queue for the life of the session and
 * nothing else reads it.</p>
 *
 * <p><b>Suspend policy: the event thread only.</b> A breakpoint stops the thread
 * that hit it and nothing else. This is what lets a probe watch a hot loop while
 * the loop keeps running (D8), and it is why a snapshot must say plainly which
 * threads are suspended and which are still executing — a stack read from a
 * running thread is not a fact, it is a race.</p>
 */
public final class DebugController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);
    private static final ObjectMapper JOURNAL = new ObjectMapper();

    /** Instances-of-type: past this, we stop counting live and go get an exact number. */
    public static final int INSTANCES_CAP = 100;
    /** The hit log is bounded — a breakpoint in a hot loop would otherwise eat the heap. */
    private static final int MAX_RECORDED_HITS = 200;

    private final VirtualMachine vm;
    private final long pid;
    private final Thread pump;

    private final Map<String, Bp> breakpoints = new ConcurrentHashMap<>();
    private final Map<Long, ThreadReference> suspended = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Map<String, Object>> fresh = new LinkedBlockingQueue<>();
    private final List<Map<String, Object>> history = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    private volatile boolean running = true;
    private volatile String vmGone;
    /** A JVM we launched is held before its first instruction until the caller starts it. */
    private volatile boolean awaitingStart;
    /** Append-only, one JSON line per hit — the thing an agent can be WOKEN by. */
    private volatile Path hitJournal;

    public DebugController(VirtualMachine vm, long pid) {
        this(vm, pid, false);
    }

    public DebugController(VirtualMachine vm, long pid, boolean suspendedAtStart) {
        this.vm = vm;
        this.pid = pid;
        this.awaitingStart = suspendedAtStart;
        this.pump = new Thread(this::pumpEvents, "jawata-debug-events");
        this.pump.setDaemon(true);
        this.pump.start();
    }

    public boolean isAwaitingStart() {
        return awaitingStart;
    }

    /**
     * What every event carries besides itself: which session it came from, and which project
     * the code belongs to.
     *
     * <p>Without this, a line in the hit stream is an orphan. With it, a watcher reading
     * {@code {"class": "com.example.Foo", "method": "bar", "projectKey": "orb"}} can hand
     * that straight to {@code get_call_hierarchy(symbol="com.example.Foo#bar")} — no search,
     * no guessing which workspace it meant. That is the whole loop closing.</p>
     */
    private volatile Map<String, Object> context = Map.of();

    public void setContext(Map<String, Object> context) {
        this.context = Map.copyOf(context);
    }

    /**
     * Stream every hit, as one JSON line, to a file — so that an agent can be WOKEN by a
     * breakpoint instead of asking about it.
     *
     * <p>An MCP server cannot push anything: it only ever speaks when spoken to, and
     * nothing it sends re-invokes an agent that is not already in a turn. But the agent's
     * own harness CAN wake it — it watches files, background jobs and subagents, and
     * re-invokes the model when one of them fires. So the hit does not travel over MCP at
     * all: it lands in this file, the agent points a file monitor at it, and every
     * breakpoint becomes a notification in its reasoning loop. The session stays live and
     * the agent stays idle in between.</p>
     *
     * <p>This is what a human gets from an IDE: you do not poll the debugger, it tells you.
     * Same thing, different channel.</p>
     */
    public void journalHitsTo(Path journal) {
        if (hitJournal != null) {
            return;   // idempotent: one journal per session, opened once
        }
        try {
            Files.createDirectories(journal.getParent());
            // Create it EMPTY and up front, so a watcher can attach before the first hit.
            // A file that springs into existence on the first event is a race for whoever
            // is trying to tail it.
            if (!Files.exists(journal)) {
                Files.writeString(journal, "");
            }
            hitJournal = journal;
        } catch (IOException e) {
            log.warn("cannot open the hit journal {}: {}", journal, e.getMessage());
        }
    }

    /** Where the hits are being streamed, if anywhere. */
    public Optional<Path> hitJournal() {
        return Optional.ofNullable(hitJournal);
    }

    private void appendToJournal(Map<String, Object> hit) {
        Path journal = hitJournal;
        if (journal == null) {
            return;
        }
        try {
            // One line per hit, flushed on write: a watcher must see it NOW, not when a
            // buffer happens to fill.
            Files.writeString(journal, JOURNAL.writeValueAsString(hit) + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // A journal that cannot be written must not take the debug session down with
            // it — the hit itself is still recorded and still waiting to be collected.
            log.warn("cannot append to the hit journal: {}", e.getMessage());
        }
    }

    /** One breakpoint, as asked for and as it actually stands. */
    private static final class Bp {
        final String id;
        final String kind;
        final String className;
        final Integer line;
        final String methodName;
        final String fieldName;
        final String condition;
        final Integer hitCount;
        final boolean internal;
        final AtomicInteger hits = new AtomicInteger();

        /** How often the CONDITION was evaluated — i.e. how often we stopped the world. */
        final AtomicInteger evaluations = new AtomicInteger();

        volatile EventRequest request;
        volatile ClassPrepareRequest deferred;
        volatile boolean bound;
        volatile String pendingReason;
        volatile String conditionError;

        Bp(String id, String kind, String className, Integer line, String methodName,
           String fieldName, String condition, Integer hitCount, boolean internal) {
            this.id = id;
            this.kind = kind;
            this.className = className;
            this.line = line;
            this.methodName = methodName;
            this.fieldName = fieldName;
            this.condition = condition;
            this.hitCount = hitCount;
            this.internal = internal;
        }

        Map<String, Object> describe() {
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("breakpointId", id);
            described.put("kind", kind);
            described.put("class", className);
            if (line != null) {
                described.put("line", line);
            }
            if (methodName != null) {
                described.put("method", methodName);
            }
            if (fieldName != null) {
                described.put("field", fieldName);
            }
            if (condition != null) {
                described.put("condition", condition);
                int tried = evaluations.get();
                described.put("conditionEvaluations", tried);
                described.put("passedOver", Math.max(0, tried - hits.get()));
                if (tried > 500) {
                    // The user is paying for this, and should know they are.
                    described.put("costWarning", "This condition has stopped the thread "
                        + tried + " times to be evaluated, and matched " + hits.get()
                        + ". Each evaluation is a round trip to the JVM, so a condition on a "
                        + "hot path slows the target substantially and shifts its timing. If "
                        + "you can, break somewhere colder, or use kind=hit_count — the JVM "
                        + "counts that one itself, without stopping anything.");
                }
            }
            if (hitCount != null) {
                described.put("hitCount", hitCount);
            }
            described.put("bound", bound);
            described.put("hits", hits.get());
            if (!bound && pendingReason != null) {
                described.put("pending", true);
                described.put("pendingReason", pendingReason);
            }
            if (conditionError != null) {
                described.put("conditionError", conditionError);
            }
            return described;
        }
    }

    /** A breakpoint could not be created — with a reason the caller can act on. */
    public static class DebugException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;

        public DebugException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ------------------------------------------------------------- the pump

    private void pumpEvents() {
        while (running) {
            EventSet set;
            try {
                set = vm.eventQueue().remove(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (com.sun.jdi.VMDisconnectedException e) {
                vmGone = "the target JVM disconnected";
                running = false;
                return;
            } catch (Exception e) {
                log.warn("debug event pump: {}", e.getMessage());
                continue;
            }
            if (set == null) {
                continue;
            }
            boolean resume = true;
            for (Event event : set) {
                try {
                    resume &= handle(event);
                } catch (Exception e) {
                    log.warn("handling {} failed: {}", event, e.getMessage());
                }
            }
            if (resume) {
                try {
                    set.resume();
                } catch (Exception e) {
                    log.debug("resuming an event set failed: {}", e.getMessage());
                }
            }
        }
    }

    /** @return true if the event set should be resumed (i.e. we are NOT stopping here). */
    private boolean handle(Event event) {
        if (event instanceof VMStartEvent) {
            // A launched target arrives here HELD, and the VMStartEvent carries a
            // suspend-all policy. Resuming it would start the program behind the caller's
            // back — before a single breakpoint is armed — and the caller's own resume()
            // would then land on an already-running VM. So we hold, and only the caller
            // starts it.
            return !awaitingStart;
        }
        if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            vmGone = "the target JVM exited";
            running = false;
            return true;
        }
        if (event instanceof ClassPrepareEvent prepared) {
            installDeferred(prepared.referenceType());
            return true;    // installed; let the class carry on
        }
        // A PROBE never holds the program — it reads what the event carries and lets it run.
        Probe probe = probeFor(event.request());
        if (probe != null) {
            return handleProbe(probe, event);
        }

        Bp bp = breakpointFor(event.request());
        if (bp == null) {
            return true;    // not ours
        }

        ThreadReference thread = threadOf(event);
        if (thread == null) {
            return true;
        }

        // A condition decides whether this is a stop at all. Evaluate it in the very
        // frame that hit — that is the only place it means anything.
        if (bp.condition != null) {
            // EVERY occurrence stops the thread and costs a round trip to evaluate — even
            // the ones that do not match. On a hot path that is the dominant cost of the
            // whole session, so we COUNT it and report it rather than let it be a mystery.
            bp.evaluations.incrementAndGet();
            try {
                Value verdict = new JdiEvaluator(thread, 0).evaluate(bp.condition);
                if (verdict instanceof com.sun.jdi.BooleanValue decision) {
                    if (!decision.value()) {
                        return true;    // not the occurrence you asked for — carry on
                    }
                    bp.conditionError = null;
                } else {
                    bp.conditionError = "the condition did not evaluate to a boolean";
                }
            } catch (JdiEvaluator.EvalException e) {
                // We STOP on a broken condition rather than swallow it. A condition that
                // silently never matches looks exactly like a bug that never happens.
                bp.conditionError = e.code + ": " + e.getMessage();
            }
        }

        bp.hits.incrementAndGet();

        // Mark the thread suspended BEFORE the hit is published. A caller that reads the
        // hit and immediately asks for the frame must not race the bookkeeping and be told
        // the thread is running when it is stopped right there.
        suspended.put(thread.uniqueID(), thread);
        record(bp, event, thread);

        if (bp.internal) {
            // A one-shot marker (step-to-line): it has done its job.
            try {
                vm.eventRequestManager().deleteEventRequest(bp.request);
            } catch (Exception e) {
                log.debug("deleting a one-shot breakpoint failed: {}", e.getMessage());
            }
            breakpoints.remove(bp.id);
        }
        if (event instanceof StepEvent) {
            // A step request fires once and must not linger — a stale one throws on the
            // next step of the same thread.
            try {
                vm.eventRequestManager().deleteEventRequest(event.request());
            } catch (Exception e) {
                log.debug("deleting the step request failed: {}", e.getMessage());
            }
            breakpoints.remove(bp.id);
        }
        return false;   // stay stopped — this is the point
    }

    private Bp breakpointFor(EventRequest request) {
        if (request == null) {
            return null;
        }
        Object owner = request.getProperty("jawata.bp");
        return owner instanceof String id ? breakpoints.get(id) : null;
    }

    private Probe probeFor(EventRequest request) {
        if (request == null) {
            return null;
        }
        Object owner = request.getProperty("jawata.probe");
        return owner instanceof String id ? probes.get(id) : null;
    }

    private static ThreadReference threadOf(Event event) {
        if (event instanceof LocatableEvent locatable) {
            return locatable.thread();
        }
        return null;
    }

    private void record(Bp bp, Event event, ThreadReference thread) {
        Map<String, Object> hit = new LinkedHashMap<>(context);
        hit.put("hitId", "hit-" + ids.incrementAndGet());
        hit.put("breakpointId", bp.internal ? "(step-to-line)" : bp.id);
        hit.put("kind", bp.kind);
        hit.put("threadId", thread.uniqueID());
        hit.put("threadName", thread.name());
        hit.put("hitNumber", bp.hits.get());
        hit.put("atMillis", System.currentTimeMillis());
        if (bp.conditionError != null) {
            hit.put("conditionError", bp.conditionError);
            hit.put("note", "Stopped because the CONDITION could not be evaluated — not "
                + "because it was true. Fix the condition; do not read this as a match.");
        }

        if (event instanceof LocatableEvent locatable) {
            hit.putAll(describeLocation(locatable.location()));
        }
        if (event instanceof ExceptionEvent thrown) {
            hit.put("exception", thrown.exception().referenceType().name());
            hit.put("caught", thrown.catchLocation() != null);
            try {
                hit.put("message", JdiValues.summary(exceptionMessage(thrown.exception(), thread)));
            } catch (Exception e) {
                hit.put("message", "(unreadable: " + e.getMessage() + ")");
            }
        }
        if (event instanceof WatchpointEvent watch) {
            hit.put("field", watch.field().name());
            hit.put("currentValue", JdiValues.summary(watch.valueCurrent()));
            if (event instanceof ModificationWatchpointEvent modified) {
                hit.put("newValue", JdiValues.summary(modified.valueToBe()));
            }
        }

        // JOURNAL FIRST, then publish. A hit that a caller can already act on but which is
        // not yet in the stream is a hit a file-watching agent would never be woken by —
        // the same "published before it was recorded" race as marking the thread suspended.
        appendToJournal(hit);

        fresh.offer(hit);
        history.add(hit);
        while (history.size() > MAX_RECORDED_HITS) {
            history.remove(0);
        }
    }

    private Value exceptionMessage(ObjectReference exception, ThreadReference thread) {
        Field message = exception.referenceType().fieldByName("detailMessage");
        return message == null ? null : exception.getValue(message);
    }

    private static Map<String, Object> describeLocation(Location location) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("class", location.declaringType().name());
        described.put("method", location.method().name());
        described.put("line", location.lineNumber());
        try {
            described.put("source", location.sourceName());
        } catch (AbsentInformationException e) {
            described.put("source", null);
        }
        return described;
    }

    // ------------------------------------------------------------ lifecycle

    private void requireLive() throws DebugException {
        if (!running || vmGone != null) {
            throw new DebugException("SESSION_TARGET_GONE",
                vmGone == null ? "the debug session is closed" : vmGone);
        }
    }

    @Override
    public void close() {
        running = false;
        pump.interrupt();
        try {
            // DISARM FIRST. If requests were still live, a thread could hit one while we
            // are letting the others go, and we would walk away from a JVM frozen at a
            // breakpoint nobody is left to release.
            vm.eventRequestManager().deleteAllBreakpoints();
            vm.eventRequestManager().deleteEventRequests(
                List.copyOf(vm.eventRequestManager().stepRequests()));

            // Now release everything we are holding. Here the drain-to-zero loop IS right:
            // nothing new can suspend, and leaving a thread frozen is the one outcome we
            // must not allow.
            for (ThreadReference thread : suspended.values()) {
                for (int guard = 0; thread.suspendCount() > 0 && guard < 32; guard++) {
                    thread.resume();
                }
            }
            if (awaitingStart) {
                awaitingStart = false;
                vm.resume();   // a target held at start, then abandoned, would hang forever
            }
        } catch (Exception e) {
            log.debug("debug teardown: {}", e.getMessage());
        }
        suspended.clear();
        breakpoints.clear();
    }

    // ---------------------------------------------------------- breakpoints

    /**
     * Set a breakpoint of one of the seven kinds. A class that is not loaded yet does
     * not fail — it is DEFERRED and says so, because "not loaded yet" and "never going
     * to bind" must not look the same.
     */
    public Map<String, Object> setBreakpoint(String kind, String className, Integer line,
                                             String methodName, String fieldName,
                                             String condition, Integer hitCount)
            throws DebugException {
        requireLive();
        String id = "bp-" + ids.incrementAndGet();
        Bp bp = new Bp(id, kind, className, line, methodName, fieldName, condition, hitCount, false);
        breakpoints.put(id, bp);
        try {
            bind(bp);
        } catch (DebugException e) {
            breakpoints.remove(id);
            throw e;
        }
        return bp.describe();
    }

    /** An internal one-shot breakpoint — how step-to-line is actually implemented. */
    private Bp oneShotAt(String className, int line) throws DebugException {
        String id = "bp-internal-" + ids.incrementAndGet();
        Bp bp = new Bp(id, "line", className, line, null, null, null, null, true);
        breakpoints.put(id, bp);
        bind(bp);
        if (!bp.bound) {
            breakpoints.remove(id);
            throw new DebugException("BREAKPOINT_UNBOUND",
                "Cannot step to " + className + ":" + line + " — " + bp.pendingReason);
        }
        return bp;
    }

    /**
     * The ONE loaded class with this name — or a refusal that names the alternatives.
     *
     * <p>{@code vm.classesByName(name)} can return several: the SAME fully-qualified name
     * loaded by different classloaders. In a plain app that is rare; in OSGi it is ordinary,
     * and the intended target here — JATS — IS OSGi, where a class can genuinely be present
     * in two bundles at once. Every caller used {@code .get(0)}: {@code redefine} patched an
     * arbitrary one of them, {@code breakpoint_set} bound to an arbitrary one, {@code
     * instances} counted an arbitrary one — each reporting success while acting on a coin
     * flip. On a hot-swap that is the difference between fixing the bug and fixing a class
     * that is not even running.</p>
     *
     * <p>So when the name is ambiguous we REFUSE and hand back the loaders, because silently
     * choosing one is the one thing worse than not choosing: it looks like it worked.</p>
     */
    private ReferenceType resolveUniqueType(String className, String action) throws DebugException {
        List<ReferenceType> loaded = vm.classesByName(className);
        if (loaded.isEmpty()) {
            throw new DebugException("TYPE_NOT_LOADED",
                className + " is not loaded in the target JVM.");
        }
        if (loaded.size() > 1) {
            StringBuilder loaders = new StringBuilder();
            for (ReferenceType type : loaded) {
                com.sun.jdi.ClassLoaderReference cl = type.classLoader();
                loaders.append("\n  - ")
                    .append(cl == null ? "the bootstrap loader" : cl.referenceType().name()
                        + "@" + cl.uniqueID());
            }
            throw new DebugException("TYPE_AMBIGUOUS",
                className + " is loaded " + loaded.size() + " times by different classloaders "
                    + "(this is normal in OSGi, and the target may be OSGi). " + action + " would "
                    + "have to guess which one you mean, and guessing wrong looks exactly like "
                    + "success. Loaders:" + loaders);
        }
        return loaded.get(0);
    }

    private void bind(Bp bp) throws DebugException {
        List<ReferenceType> loaded = vm.classesByName(bp.className);
        if (loaded.isEmpty()) {
            // Defer: install the moment the class arrives.
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(bp.className);
            prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            prepare.enable();
            bp.deferred = prepare;
            bp.bound = false;
            bp.pendingReason = bp.className + " is not loaded yet — the breakpoint will "
                + "install the moment it is, and 'bound' will flip to true.";
            return;
        }
        install(bp, loaded.get(0));
    }

    private void installDeferred(ReferenceType type) {
        for (Bp bp : breakpoints.values()) {
            if (!bp.bound && type.name().equals(bp.className)) {
                try {
                    install(bp, type);
                    if (bp.deferred != null) {
                        vm.eventRequestManager().deleteEventRequest(bp.deferred);
                        bp.deferred = null;
                    }
                } catch (DebugException e) {
                    bp.pendingReason = e.getMessage();
                }
            }
        }
    }

    private void install(Bp bp, ReferenceType type) throws DebugException {
        EventRequestManager erm = vm.eventRequestManager();
        EventRequest request = switch (bp.kind) {
            case "line" -> lineRequest(erm, type, bp);
            case "method" -> methodRequest(erm, type, bp);
            case "conditional" -> lineRequest(erm, type, bp);
            case "hit_count" -> lineRequest(erm, type, bp);
            case "exception" -> exceptionRequest(erm, type);
            case "field_access" -> accessRequest(erm, type, bp);
            case "field_write" -> writeRequest(erm, type, bp);
            default -> throw new DebugException("BREAKPOINT_UNKNOWN_KIND",
                "Unknown breakpoint kind '" + bp.kind + "'. One of: line, method, "
                    + "conditional, hit_count, exception, field_access, field_write.");
        };
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.putProperty("jawata.bp", bp.id);
        if (bp.hitCount != null && bp.hitCount > 1) {
            // JDI counts for us: the request fires on the Nth occurrence.
            request.addCountFilter(bp.hitCount);
        }
        request.enable();
        bp.request = request;
        bp.bound = true;
        bp.pendingReason = null;
    }

    private BreakpointRequest lineRequest(EventRequestManager erm, ReferenceType type, Bp bp)
            throws DebugException {
        if (bp.line == null) {
            throw new DebugException("BREAKPOINT_NEEDS_LINE",
                "A " + bp.kind + " breakpoint needs a line.");
        }
        List<Location> locations;
        try {
            locations = type.locationsOfLine(bp.line);
        } catch (AbsentInformationException e) {
            throw new DebugException("BREAKPOINT_NO_LINE_TABLE",
                type.name() + " was compiled without line numbers — a line breakpoint "
                    + "cannot be placed in it. Break on the method instead (kind=method).");
        }
        if (locations.isEmpty()) {
            throw new DebugException("BREAKPOINT_NO_CODE_AT_LINE",
                "There is no executable code at " + type.name() + ":" + bp.line
                    + " (a blank line, a comment, or a declaration).");
        }
        return erm.createBreakpointRequest(locations.get(0));
    }

    private BreakpointRequest methodRequest(EventRequestManager erm, ReferenceType type, Bp bp)
            throws DebugException {
        if (bp.methodName == null) {
            throw new DebugException("BREAKPOINT_NEEDS_METHOD",
                "A method breakpoint needs a method name.");
        }
        List<Method> methods = type.methodsByName(bp.methodName);
        if (methods.isEmpty()) {
            throw new DebugException("BREAKPOINT_UNKNOWN_METHOD",
                type.name() + " has no method '" + bp.methodName + "'.");
        }
        Location entry = methods.get(0).location();
        if (entry == null) {
            throw new DebugException("BREAKPOINT_ABSTRACT_METHOD",
                bp.methodName + " is abstract or native — it has no code to break on.");
        }
        return erm.createBreakpointRequest(entry);
    }

    private ExceptionRequest exceptionRequest(EventRequestManager erm, ReferenceType type) {
        return erm.createExceptionRequest(type, true, true);
    }

    private EventRequest accessRequest(EventRequestManager erm, ReferenceType type, Bp bp)
            throws DebugException {
        if (!vm.canWatchFieldAccess()) {
            throw new DebugException("CAPABILITY_ABSENT",
                "This JVM cannot watch field ACCESS (canWatchFieldAccess=false).");
        }
        return erm.createAccessWatchpointRequest(field(type, bp));
    }

    private EventRequest writeRequest(EventRequestManager erm, ReferenceType type, Bp bp)
            throws DebugException {
        if (!vm.canWatchFieldModification()) {
            throw new DebugException("CAPABILITY_ABSENT",
                "This JVM cannot watch field WRITES (canWatchFieldModification=false).");
        }
        return erm.createModificationWatchpointRequest(field(type, bp));
    }

    private Field field(ReferenceType type, Bp bp) throws DebugException {
        return field(type, bp.fieldName);
    }

    private Field field(ReferenceType type, String fieldName) throws DebugException {
        if (fieldName == null) {
            throw new DebugException("BREAKPOINT_NEEDS_FIELD",
                "A field watchpoint needs a field name.");
        }
        Field field = type.fieldByName(fieldName);
        if (field == null) {
            throw new DebugException("BREAKPOINT_UNKNOWN_FIELD",
                type.name() + " has no field '" + fieldName + "'.");
        }
        return field;
    }

    public List<Map<String, Object>> listBreakpoints() {
        return breakpoints.values().stream()
            .filter(bp -> !bp.internal)
            .map(Bp::describe)
            .toList();
    }

    public boolean clearBreakpoint(String breakpointId) {
        Bp bp = breakpoints.remove(breakpointId);
        if (bp == null) {
            return false;
        }
        try {
            if (bp.request != null) {
                vm.eventRequestManager().deleteEventRequest(bp.request);
            }
            if (bp.deferred != null) {
                vm.eventRequestManager().deleteEventRequest(bp.deferred);
            }
        } catch (Exception e) {
            log.debug("clearing {} failed: {}", breakpointId, e.getMessage());
        }
        return true;
    }

    // ----------------------------------------------------------- hits, wait

    /**
     * The next hit, waiting up to {@code timeoutMillis} for one. A timeout is NOT an
     * error — it is the honest answer "nothing has hit yet"; the caller polls again.
     */
    public Optional<Map<String, Object>> awaitHit(long timeoutMillis) throws DebugException {
        requireLive();
        try {
            return Optional.ofNullable(fresh.poll(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> hits() {
        return List.copyOf(history);
    }

    public int pendingHits() {
        return fresh.size();
    }

    public boolean isLive() {
        return running && vmGone == null;
    }

    // ------------------------------------------------------------ snapshots

    /**
     * What every thread is doing. The one thing this must never do is present a stack
     * read from a RUNNING thread as fact — so a running thread reports its state and no
     * stack, and says why.
     */
    public Map<String, Object> threads() throws DebugException {
        requireLive();
        List<Map<String, Object>> described = new ArrayList<>();
        for (ThreadReference thread : vm.allThreads()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threadId", thread.uniqueID());
            row.put("name", thread.name());
            row.put("state", stateOf(thread));
            boolean isSuspended = thread.isSuspended();
            row.put("suspended", isSuspended);
            if (!isSuspended) {
                row.put("stack", null);
                row.put("stackUnavailable",
                    "this thread is RUNNING — its stack cannot be read without stopping it");
            }
            described.add(row);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("threads", described);
        snapshot.put("count", described.size());
        snapshot.put("suspendedCount", described.stream()
            .filter(t -> Boolean.TRUE.equals(t.get("suspended"))).count());
        return snapshot;
    }

    private static Object safeIsSuspended(ThreadReference thread) {
        try {
            return thread.isSuspended();
        } catch (Exception e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static Object safeSuspendCount(ThreadReference thread) {
        try {
            return thread.suspendCount();
        } catch (Exception e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String stateOf(ThreadReference thread) {
        try {
            return switch (thread.status()) {
                case ThreadReference.THREAD_STATUS_RUNNING -> "RUNNING";
                case ThreadReference.THREAD_STATUS_SLEEPING -> "SLEEPING";
                case ThreadReference.THREAD_STATUS_MONITOR -> "BLOCKED_ON_MONITOR";
                case ThreadReference.THREAD_STATUS_WAIT -> "WAITING";
                case ThreadReference.THREAD_STATUS_ZOMBIE -> "TERMINATED";
                case ThreadReference.THREAD_STATUS_NOT_STARTED -> "NOT_STARTED";
                default -> "UNKNOWN";
            };
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /** The stack of one suspended thread, with the requested frame expanded. */
    public Map<String, Object> snapshot(long threadId, int frameIndex, int depth,
                                        int maxItems, int maxBytes) throws DebugException {
        requireLive();
        ThreadReference thread = readableThread(threadId);

        List<StackFrame> frames;
        try {
            frames = thread.frames();
        } catch (Exception e) {
            // Say what the VM actually reports, not just that we failed. "It threw" is not
            // a diagnosis; "the thread is RUNNING with suspendCount 0" is.
            throw new DebugException("SNAPSHOT_UNAVAILABLE",
                "Cannot read the stack of thread " + threadId + " (" + thread.name() + "): "
                    + e.getClass().getSimpleName() + " — the VM reports state=" + stateOf(thread)
                    + ", isSuspended=" + safeIsSuspended(thread)
                    + ", suspendCount=" + safeSuspendCount(thread));
        }
        List<Map<String, Object>> stack = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>(describeLocation(frames.get(i).location()));
            row.put("frameIndex", i);
            stack.add(row);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("threadId", threadId);
        snapshot.put("threadName", thread.name());
        snapshot.put("state", stateOf(thread));
        snapshot.put("suspended", true);
        snapshot.put("stack", stack);
        snapshot.put("stackDepth", stack.size());

        if (frameIndex < 0 || frameIndex >= frames.size()) {
            throw new DebugException("FRAME_OUT_OF_RANGE",
                "frame " + frameIndex + " does not exist — the stack is " + frames.size()
                    + " deep.");
        }
        snapshot.put("frame", describeFrame(frames.get(frameIndex), frameIndex,
            depth, maxItems, maxBytes));
        return snapshot;
    }

    private Map<String, Object> describeFrame(StackFrame frame, int frameIndex, int depth,
                                              int maxItems, int maxBytes) {
        Map<String, Object> described = new LinkedHashMap<>(describeLocation(frame.location()));
        described.put("frameIndex", frameIndex);

        ObjectReference self = frame.thisObject();
        described.put("this", self == null
            ? null
            : JdiValues.expand(self, depth, maxItems, maxBytes));
        if (self == null) {
            described.put("thisAbsent", "the frame is static — there is no receiver");
        }

        // Arguments come from the method's own signature and are always available.
        List<Map<String, Object>> args = new ArrayList<>();
        try {
            List<Value> values = frame.getArgumentValues();
            List<String> names = argumentNames(frame, values.size());
            for (int i = 0; i < values.size(); i++) {
                Map<String, Object> arg = new LinkedHashMap<>();
                arg.put("name", names.get(i));
                arg.put("value", JdiValues.expand(values.get(i), depth, maxItems, maxBytes));
                args.add(arg);
            }
        } catch (Exception e) {
            described.put("argumentsUnavailable", String.valueOf(e.getMessage()));
        }
        described.put("arguments", args);

        // Locals need a local-variable table. Without -g there simply is none, and that
        // is a fact about the target's compilation — not an empty result.
        try {
            Map<String, Object> locals = new LinkedHashMap<>();
            for (LocalVariable local : frame.visibleVariables()) {
                if (local.isArgument()) {
                    continue;
                }
                locals.put(local.name(),
                    JdiValues.expand(frame.getValue(local), depth, maxItems, maxBytes));
            }
            described.put("locals", locals);
        } catch (AbsentInformationException e) {
            described.put("locals", Map.of());
            described.put("localsUnavailable",
                "this class was compiled without -g (no local-variable table), so locals "
                    + "cannot be read — arguments still can");
        } catch (Exception e) {
            described.put("localsUnavailable", String.valueOf(e.getMessage()));
        }
        return described;
    }

    private List<String> argumentNames(StackFrame frame, int count) {
        List<String> names = new ArrayList<>();
        try {
            for (LocalVariable local : frame.location().method().arguments()) {
                names.add(local.name());
            }
        } catch (AbsentInformationException e) {
            names.clear();
        }
        while (names.size() < count) {
            names.add("arg" + names.size());   // honest placeholder, never an invented name
        }
        return names;
    }

    // ------------------------------------------------------------- evaluate

    public Map<String, Object> evaluate(long threadId, int frameIndex, String expression)
            throws DebugException {
        requireLive();
        // The frame must be stopped AND readable — a force-returned frame is neither there
        // nor evaluable, however stopped the thread is.
        ThreadReference thread = readableThread(threadId);
        JdiEvaluator evaluator = new JdiEvaluator(thread, frameIndex);
        try {
            Value result = evaluator.evaluate(expression);
            Map<String, Object> evaluated = new LinkedHashMap<>();
            evaluated.put("expression", expression);
            evaluated.put("value", JdiValues.expand(result));
            evaluated.put("summary", JdiValues.summary(result));
            evaluated.put("invokedMethod", evaluator.invokedMethod());
            recordEvaluation(expression, evaluator.invokedMethod());
            return evaluated;
        } catch (JdiEvaluator.EvalException e) {
            // It ran code in the target and THEN failed? That still happened. Record it.
            if (evaluator.invokedMethod()) {
                recordEvaluation(expression, true);
            }
            throw new DebugException(e.code, e.getMessage());
        }
    }

    /**
     * An evaluation is part of what this session DID to the program, so it belongs in the
     * session's own account of itself — the spec requires evaluation, state mutation and
     * hot-swap alike to be "explicit actions recorded in the session outcome", and it says
     * plainly that an evaluation "may invoke methods and is side-effecting unless proven
     * otherwise".
     *
     * <p>It was recorded nowhere. So an agent could evaluate {@code queue.clear()} in a
     * suspended frame and {@code status} would still answer {@code programIsUnmodified: true}
     * — a confident false statement about a program we had just edited, from the one field
     * whose entire job is to be trustworthy about that. Sprint-24 audit (T1.15).</p>
     */
    private void recordEvaluation(String expression, boolean invokedMethod) {
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("expression", expression);
        evaluation.put("invokedMethod", invokedMethod);
        evaluation.put("atMillis", System.currentTimeMillis());
        evaluation.put("evaluationId", "eval-" + ids.incrementAndGet());
        evaluations.add(evaluation);
        appendToJournal(evaluation);
    }

    // -------------------------------------------------------------- control

    /** Step in / over / out. The step event lands like any other hit. */
    public Map<String, Object> step(long threadId, String mode) throws DebugException {
        requireLive();
        ThreadReference thread = suspendedThread(threadId);
        int depth = switch (mode) {
            case "in" -> StepRequest.STEP_INTO;
            case "over" -> StepRequest.STEP_OVER;
            case "out" -> StepRequest.STEP_OUT;
            default -> throw new DebugException("STEP_UNKNOWN_MODE",
                "Step mode must be in, over, out, or to_line — got '" + mode + "'.");
        };
        EventRequestManager erm = vm.eventRequestManager();
        // A leftover step request for this thread makes the next one throw.
        for (StepRequest stale : new ArrayList<>(erm.stepRequests())) {
            if (thread.equals(stale.thread())) {
                erm.deleteEventRequest(stale);
            }
        }
        String id = "step-" + ids.incrementAndGet();
        Bp marker = new Bp(id, "step", null, null, null, null, null, null, false);
        breakpoints.put(id, marker);

        StepRequest request = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.putProperty("jawata.bp", id);
        request.addCountFilter(1);
        request.enable();
        marker.request = request;
        marker.bound = true;

        resumeThread(thread);
        return Map.of("stepping", mode, "threadId", threadId,
            "note", "The thread is running the step. Poll debug(action=wait) for where it lands.");
    }

    /** Step to a specific line — a one-shot breakpoint there, then run to it. */
    public Map<String, Object> stepToLine(long threadId, String className, int line)
            throws DebugException {
        requireLive();
        ThreadReference thread = suspendedThread(threadId);
        Bp marker = oneShotAt(className, line);
        resumeThread(thread);
        return Map.of("steppingTo", className + ":" + line, "threadId", threadId,
            "breakpointId", marker.id,
            "note", "Running to that line. Poll debug(action=wait). If the line is never "
                + "reached, the thread simply keeps running — that is an answer too.");
    }

    public Map<String, Object> resume(Long threadId) throws DebugException {
        requireLive();
        if (threadId == null) {
            // The first resume of a launched target STARTS it: the whole VM is held, not
            // one thread, because nothing has run yet.
            if (awaitingStart) {
                awaitingStart = false;
                vm.resume();
                return Map.of("resumed", "the program",
                    "scope", "the target was started (it had not run yet)");
            }
            int count = suspended.size();
            for (ThreadReference thread : List.copyOf(suspended.values())) {
                resumeThread(thread);
            }
            return Map.of("resumed", count, "scope", "every suspended thread");
        }
        ThreadReference thread = suspendedThread(threadId);
        resumeThread(thread);
        return Map.of("resumed", 1, "threadId", threadId);
    }

    /**
     * Release the ONE suspension this session holds on the thread — exactly one.
     *
     * <p>Not a "resume until suspendCount reaches zero" loop. That looks safer and is a
     * race: the count is re-read from the VM on each turn, so a breakpoint hit landing
     * between two turns is mistaken for leftover suspension and resumed away — silently
     * cancelling the very breakpoint that just fired, and leaving a recorded hit whose
     * thread is running. An event set with an event-thread policy suspends the thread
     * once; we release exactly that one.</p>
     */
    private void resumeThread(ThreadReference thread) {
        suspended.remove(thread.uniqueID());
        // The thread is moving, which is exactly what rebuilds JDI's cached stack — so
        // whatever was stale about it no longer is.
        staleStacks.remove(thread.uniqueID());
        thread.resume();
    }

    /** A thread whose stack we can actually READ — not merely one that is stopped. */
    private ThreadReference readableThread(long threadId) throws DebugException {
        ThreadReference thread = suspendedThread(threadId);
        if (staleStacks.contains(threadId)) {
            throw new DebugException("STACK_STALE_AFTER_FORCE_RETURN",
                "This thread's frame was force-returned. The JVM has popped it, but JDI does "
                    + "not rebuild its cached stack until the thread moves — so anything we "
                    + "showed you now would be the method you just abandoned, which is no "
                    + "longer there. Step once (debug(action=step, mode=over)), then read it.");
        }
        return thread;
    }

    private ThreadReference suspendedThread(long threadId) throws DebugException {
        ThreadReference thread = suspended.get(threadId);
        if (thread == null) {
            throw new DebugException("THREAD_NOT_SUSPENDED",
                "Thread " + threadId + " is not suspended at a breakpoint. Its stack and "
                    + "locals cannot be read while it runs — that would be a race, not a "
                    + "reading. Suspended right now: " + suspended.keySet());
        }
        return thread;
    }

    // ------------------------------------------------------- D8: dev probes

    /** Probes stream values out of a running program; they must never fill the heap. */
    public static final int DEFAULT_PROBE_BUDGET = 1000;
    private static final int MAX_PROBE_EVENTS_KEPT = 500;

    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> probeEvents = new CopyOnWriteArrayList<>();

    /**
     * A watcher on a running program — <b>it does not stop it</b>.
     *
     * <p>This is the difference that matters on a live simulation: a breakpoint answers
     * "what is the state HERE?" by stopping the world, which changes the timing of
     * everything that follows. A probe answers "what values flow through here?" while the
     * program keeps running at full speed.</p>
     *
     * <p>The catch, stated rather than hidden: a JDI event only carries what the JVM puts
     * in it. A field watch carries the value, and a method exit carries the return value —
     * those are FREE. But reading a frame's locals requires the thread to be STOPPED, so a
     * logpoint that captures expressions stops it on every pass, reads, and lets it go. Such
     * a probe declares {@code perturbs: true}.</p>
     *
     * <p><b>And it is not cheap.</b> Each captured expression is a round trip, and one that
     * invokes a method costs a real invocation in the target. On a hot path the thread can be
     * stopped for a large fraction of its time — measured, not guessed: a capture containing a
     * method call, on a 25 ms loop, kept the thread suspended so much that a 50 ms poll rarely
     * caught it running. That is not "a few microseconds"; it is a different program. A probe
     * that quietly changed the timing of the race you were hunting would be worse than no
     * probe at all, so we say so.</p>
     */
    private static final class Probe {
        final String id;
        final String kind;
        final String className;
        final String describe;
        final List<String> capture;
        final boolean perturbs;
        final int budget;
        final AtomicInteger seen = new AtomicInteger();
        final List<EventRequest> requests = new ArrayList<>();
        volatile boolean stopped;
        volatile String stoppedReason;

        Probe(String id, String kind, String className, String describe, List<String> capture,
              boolean perturbs, int budget) {
            this.id = id;
            this.kind = kind;
            this.className = className;
            this.describe = describe;
            this.capture = capture;
            this.perturbs = perturbs;
            this.budget = budget;
        }

        Map<String, Object> report() {
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("probeId", id);
            described.put("kind", kind);
            described.put("at", describe);
            described.put("events", seen.get());
            described.put("budget", budget);
            described.put("perturbs", perturbs);
            described.put("suspendsTarget", perturbs);
            if (!capture.isEmpty()) {
                described.put("capture", capture);
            }
            if (stopped) {
                described.put("stopped", true);
                described.put("stoppedReason", stoppedReason);
            }
            return described;
        }
    }

    /**
     * Watch a running program without stopping it.
     *
     * @param kind    field_watch | method_trace | logpoint
     * @param capture logpoint only: expressions to evaluate at each pass (this is what makes
     *                a logpoint perturbing — locals cannot be read from a running thread)
     */
    public Map<String, Object> setProbe(String kind, String className, Integer line,
                                        String methodName, String fieldName,
                                        List<String> capture, Integer budget)
            throws DebugException {
        requireLive();
        if (vm.classesByName(className).isEmpty()) {
            throw new DebugException("TYPE_NOT_LOADED",
                className + " is not loaded yet. A probe watches a running program, so the "
                    + "class has to be in it — start the program first, or break on it once.");
        }
        ReferenceType type = resolveUniqueType(className,
            "Probing " + className);
        List<String> captures = capture == null ? List.of() : List.copyOf(capture);
        int bound = budget == null || budget <= 0 ? DEFAULT_PROBE_BUDGET : budget;
        String id = "probe-" + ids.incrementAndGet();

        EventRequestManager erm = vm.eventRequestManager();
        Probe probe;
        try {
            switch (kind) {
                case "field_watch" -> {
                    Field field = field(type, fieldName);
                    probe = new Probe(id, kind, className, className + "#" + fieldName,
                        captures, false, bound);
                    if (vm.canWatchFieldModification()) {
                        probe.requests.add(erm.createModificationWatchpointRequest(field));
                    }
                    if (vm.canWatchFieldAccess()) {
                        probe.requests.add(erm.createAccessWatchpointRequest(field));
                    }
                    if (probe.requests.isEmpty()) {
                        throw new DebugException("CAPABILITY_ABSENT",
                            "This JVM cannot watch fields at all.");
                    }
                }
                case "method_trace" -> {
                    if (methodName == null) {
                        throw new DebugException("PROBE_NEEDS_METHOD",
                            "method_trace needs a method name.");
                    }
                    probe = new Probe(id, kind, className, className + "#" + methodName + "()",
                        captures, false, bound);
                    com.sun.jdi.request.MethodEntryRequest entry = erm.createMethodEntryRequest();
                    entry.addClassFilter(type);
                    probe.requests.add(entry);
                    com.sun.jdi.request.MethodExitRequest exit = erm.createMethodExitRequest();
                    exit.addClassFilter(type);
                    probe.requests.add(exit);
                }
                case "logpoint" -> {
                    if (line == null) {
                        throw new DebugException("PROBE_NEEDS_LINE", "A logpoint needs a line.");
                    }
                    List<Location> locations;
                    try {
                        locations = type.locationsOfLine(line);
                    } catch (AbsentInformationException e) {
                        throw new DebugException("BREAKPOINT_NO_LINE_TABLE",
                            className + " was compiled without line numbers.");
                    }
                    if (locations.isEmpty()) {
                        throw new DebugException("BREAKPOINT_NO_CODE_AT_LINE",
                            "No executable code at " + className + ":" + line + ".");
                    }
                    // Capturing expressions means reading a frame, and a frame can only be
                    // read while the thread is stopped. So THIS probe stops it — briefly, and
                    // it resumes itself — and it says so.
                    probe = new Probe(id, kind, className, className + ":" + line,
                        captures, !captures.isEmpty(), bound);
                    probe.requests.add(erm.createBreakpointRequest(locations.get(0)));
                }
                default -> throw new DebugException("PROBE_UNKNOWN_KIND",
                    "Unknown probe kind '" + kind + "'. One of: field_watch, method_trace, "
                        + "logpoint.");
            }
        } catch (DebugException e) {
            throw e;
        } catch (Exception e) {
            throw new DebugException("PROBE_FAILED",
                "Cannot install the probe: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }

        for (EventRequest request : probe.requests) {
            // SUSPEND_NONE is the whole point: the program runs on. A logpoint that captures
            // expressions is the one exception, and it is flagged as perturbing.
            request.setSuspendPolicy(probe.perturbs
                ? EventRequest.SUSPEND_EVENT_THREAD : EventRequest.SUSPEND_NONE);
            request.putProperty("jawata.probe", id);
            request.enable();
        }
        probes.put(id, probe);
        return probe.report();
    }

    /** @return true when the event set should be resumed (a probe never holds the program). */
    private boolean handleProbe(Probe probe, Event event) {
        if (probe.stopped) {
            return true;
        }
        int seen = probe.seen.incrementAndGet();

        Map<String, Object> streamed = new LinkedHashMap<>(context);
        streamed.put("probeId", probe.id);
        streamed.put("kind", probe.kind);
        streamed.put("event", "probe");
        streamed.put("sequence", seen);
        streamed.put("atMillis", System.currentTimeMillis());

        ThreadReference thread = threadOf(event);
        if (thread != null) {
            streamed.put("threadName", thread.name());
            streamed.put("threadId", thread.uniqueID());
        }
        if (event instanceof LocatableEvent locatable) {
            streamed.putAll(describeLocation(locatable.location()));
        }

        // What the EVENT carries is free — no frame, no stopping.
        if (event instanceof WatchpointEvent watch) {
            streamed.put("field", watch.field().name());
            streamed.put("value", JdiValues.summary(watch.valueCurrent()));
            if (event instanceof ModificationWatchpointEvent modified) {
                streamed.put("access", "write");
                streamed.put("newValue", JdiValues.summary(modified.valueToBe()));
            } else {
                streamed.put("access", "read");
            }
        }
        if (event instanceof com.sun.jdi.event.MethodEntryEvent) {
            streamed.put("trace", "entry");
        }
        if (event instanceof com.sun.jdi.event.MethodExitEvent exit) {
            streamed.put("trace", "exit");
            if (vm.canGetMethodReturnValues()) {
                streamed.put("returned", JdiValues.summary(exit.returnValue()));
            } else {
                streamed.put("returned", null);
                streamed.put("returnedUnavailable",
                    "this JVM does not report method return values (canGetMethodReturnValues"
                        + "=false)");
            }
        }

        // And what it does NOT carry — locals — costs a stop. Only a capturing logpoint pays.
        if (!probe.capture.isEmpty() && thread != null) {
            Map<String, Object> captured = new LinkedHashMap<>();
            for (String expression : probe.capture) {
                try {
                    captured.put(expression,
                        JdiValues.summary(new JdiEvaluator(thread, 0).evaluate(expression)));
                } catch (JdiEvaluator.EvalException e) {
                    captured.put(expression, e.code + ": " + e.getMessage());
                }
            }
            streamed.put("captured", captured);
        }

        appendToJournal(streamed);
        probeEvents.add(streamed);
        while (probeEvents.size() > MAX_PROBE_EVENTS_KEPT) {
            probeEvents.remove(0);
        }

        if (seen >= probe.budget) {
            // A probe on a hot path would otherwise stream until the disk fills. Stop, and
            // SAY that the stream is not the whole story.
            probe.stopped = true;
            probe.stoppedReason = "budget of " + probe.budget + " events reached — the probe is "
                + "off. The program went on running; what you have is the FIRST " + probe.budget
                + " events, not all of them.";
            for (EventRequest request : probe.requests) {
                try {
                    request.disable();
                } catch (Exception e) {
                    log.debug("disabling a spent probe failed: {}", e.getMessage());
                }
            }
        }
        return true;   // ALWAYS resume: a probe watches, it does not hold
    }

    public List<Map<String, Object>> listProbes() {
        return probes.values().stream().map(Probe::report).toList();
    }

    /**
     * A probe's own account of itself — crucially its TRUE event count ({@code events}) and
     * whether it stopped on its budget. A caller that reads events out of the bounded ring
     * needs this to know whether the ring dropped any: {@code events} counts every event the
     * probe fired; the ring keeps only the last {@link #MAX_PROBE_EVENTS_KEPT}. Empty when
     * there is no such probe.
     */
    public Optional<Map<String, Object>> probeReport(String probeId) {
        Probe probe = probes.get(probeId);
        return probe == null ? Optional.empty() : Optional.of(probe.report());
    }

    public boolean clearProbe(String probeId) {
        Probe probe = probes.remove(probeId);
        if (probe == null) {
            return false;
        }
        for (EventRequest request : probe.requests) {
            try {
                vm.eventRequestManager().deleteEventRequest(request);
            } catch (Exception e) {
                log.debug("clearing probe {} failed: {}", probeId, e.getMessage());
            }
        }
        return true;
    }

    /** The values a probe has streamed — bounded, newest last. */
    public List<Map<String, Object>> probeEvents(String probeId) {
        if (probeId == null) {
            return List.copyOf(probeEvents);
        }
        return probeEvents.stream()
            .filter(e -> probeId.equals(e.get("probeId")))
            .toList();
    }

    // --------------------------------------------------- D7: hypothesis testing

    /**
     * Every change we made to the target, in order.
     *
     * <p>This exists because a debugged program is no longer the program you started.
     * Once a value has been overwritten or a frame popped, any conclusion drawn from what
     * happens next is a conclusion about a program WE edited — and a session that cannot
     * say what it changed cannot be trusted to report what it found.</p>
     */
    private final List<Map<String, Object>> mutations = new CopyOnWriteArrayList<>();

    /**
     * Threads whose cached stack JDI has not rebuilt yet (see {@link #forceReturn}). We
     * would rather refuse to show a stack than show one that is no longer there.
     */
    private final java.util.Set<Long> staleStacks = java.util.concurrent.ConcurrentHashMap
        .newKeySet();

    /** Every expression this session ran in the target — see {@link #recordEvaluation}. */
    private final List<Map<String, Object>> evaluations = new CopyOnWriteArrayList<>();

    public List<Map<String, Object>> mutations() {
        return List.copyOf(mutations);
    }

    public List<Map<String, Object>> evaluations() {
        return List.copyOf(evaluations);
    }

    /**
     * Could this session have changed the program? Only "no" when we neither mutated its
     * state NOR ran any of its code — an evaluation that invoked a method might have changed
     * anything, and we cannot prove otherwise (we do not know that {@code size()} is pure).
     * The honest answer to "is this still the program you started?" is therefore no.
     */
    public boolean programIsUnmodified() {
        return mutations.isEmpty()
            && evaluations.stream().noneMatch(e -> Boolean.TRUE.equals(e.get("invokedMethod")));
    }

    private Map<String, Object> recordMutation(String kind, Map<String, Object> detail) {
        Map<String, Object> mutation = new LinkedHashMap<>(detail);
        mutation.put("mutation", kind);
        mutation.put("atMillis", System.currentTimeMillis());
        mutation.put("mutationId", "mut-" + ids.incrementAndGet());
        mutations.add(mutation);
        appendToJournal(mutation);   // the watcher sees what we did, not only what it hit
        return mutation;
    }

    /**
     * "What if this value were X?" — overwrite a local or a field in the live program and
     * let it run on.
     */
    public Map<String, Object> setValue(long threadId, int frameIndex, String name,
                                        String expression) throws DebugException {
        requireLive();
        ThreadReference thread = suspendedThread(threadId);

        // Evaluate FIRST: the expression may invoke a method, which resumes the thread and
        // invalidates every frame we might be holding.
        Value newValue;
        try {
            newValue = new JdiEvaluator(thread, frameIndex).evaluate(expression);
        } catch (JdiEvaluator.EvalException e) {
            throw new DebugException(e.code, e.getMessage());
        }

        StackFrame frame;
        try {
            frame = thread.frame(frameIndex);
        } catch (Exception e) {
            throw new DebugException("FRAME_OUT_OF_RANGE",
                "frame " + frameIndex + " is not available: " + e.getMessage());
        }

        Object oldValue;
        String target;
        try {
            LocalVariable local = localNamed(frame, name);
            if (local != null) {
                oldValue = JdiValues.summary(frame.getValue(local));
                frame.setValue(local, newValue);
                target = "local";
            } else {
                oldValue = setField(frame, name, newValue);
                target = "field";
            }
        } catch (DebugException e) {
            throw e;
        } catch (com.sun.jdi.InvalidTypeException e) {
            throw new DebugException("SET_VALUE_TYPE_MISMATCH",
                "'" + expression + "' does not fit " + name + ": " + e.getMessage()
                    + ". The JVM will not let a value of the wrong type into a slot — and "
                    + "that refusal is protecting you.");
        } catch (Exception e) {
            throw new DebugException("SET_VALUE_FAILED",
                "Cannot set " + name + ": " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }

        return recordMutation("set_value", new LinkedHashMap<>(Map.of(
            "target", target,
            "name", name,
            "from", String.valueOf(oldValue),
            "to", String.valueOf(JdiValues.summary(newValue)),
            "expression", expression,
            "threadId", threadId)));
    }

    private LocalVariable localNamed(StackFrame frame, String name) {
        try {
            return frame.visibleVariableByName(name);
        } catch (Exception e) {
            return null;   // no local-variable table, or no such local — try a field next
        }
    }

    /** @return the value that was there before. */
    private Object setField(StackFrame frame, String name, Value newValue) throws Exception {
        ObjectReference self = frame.thisObject();
        if (self != null) {
            Field field = self.referenceType().fieldByName(name);
            if (field != null && !field.isStatic()) {
                Object old = JdiValues.summary(self.getValue(field));
                self.setValue(field, newValue);
                return old;
            }
        }
        ReferenceType declaring = frame.location().declaringType();
        Field field = declaring.fieldByName(name);
        if (field == null) {
            throw new DebugException("SET_VALUE_UNKNOWN_NAME",
                "'" + name + "' is not a visible local or field in this frame. (If it is a "
                    + "local, the class may have been compiled without -g, which leaves no "
                    + "local-variable table to write into.)");
        }
        if (!field.isStatic() || !(declaring instanceof ClassType classType)) {
            throw new DebugException("SET_VALUE_UNREACHABLE_FIELD",
                "'" + name + "' cannot be written from this frame.");
        }
        Object old = JdiValues.summary(declaring.getValue(field));
        classType.setValue(field, newValue);
        return old;
    }

    /**
     * "What if this method had returned X?" — abandon the rest of the method and return the
     * given value to its caller, right now.
     */
    public Map<String, Object> forceReturn(long threadId, String expression)
            throws DebugException {
        requireLive();
        if (!vm.canForceEarlyReturn()) {
            throw new DebugException("CAPABILITY_ABSENT",
                "This JVM cannot force an early return (canForceEarlyReturn=false).");
        }
        ThreadReference thread = suspendedThread(threadId);

        Value value;
        try {
            value = new JdiEvaluator(thread, 0).evaluate(expression);
        } catch (JdiEvaluator.EvalException e) {
            throw new DebugException(e.code, e.getMessage());
        }

        String abandoned;
        try {
            abandoned = thread.frame(0).location().method().name();
            thread.forceEarlyReturn(value);
        } catch (com.sun.jdi.InvalidTypeException e) {
            throw new DebugException("FORCE_RETURN_TYPE_MISMATCH",
                "'" + expression + "' is not the type this method returns: " + e.getMessage());
        } catch (Exception e) {
            throw new DebugException("FORCE_RETURN_FAILED",
                "Cannot force a return: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // JDI QUIRK, and it would otherwise make us lie: forceEarlyReturn pops the frame in
        // the VM but does NOT reset JDI's cached stack for the thread — so frames() keeps
        // handing back the method we just abandoned. (popFrames DOES reset it; this one does
        // not.) The cache is rebuilt only when the thread next moves. Rather than serve a
        // frame that no longer exists, we mark the stack stale and refuse to read it until
        // the thread has stepped or resumed.
        staleStacks.add(threadId);

        Map<String, Object> mutation = recordMutation("force_return", new LinkedHashMap<>(Map.of(
            "method", abandoned,
            "returned", String.valueOf(JdiValues.summary(value)),
            "expression", expression,
            "threadId", threadId,
            "note", "The rest of " + abandoned + "() did NOT run — side effects included. The "
                + "thread is suspended in the caller; step once before reading the stack.")));
        suspended.put(thread.uniqueID(), thread);
        return mutation;
    }

    /**
     * "Let me try that again." — pop the frame, putting the thread back at the call site,
     * about to make the call afresh.
     */
    public Map<String, Object> popFrame(long threadId, int frameIndex) throws DebugException {
        requireLive();
        if (!vm.canPopFrames()) {
            throw new DebugException("CAPABILITY_ABSENT",
                "This JVM cannot pop frames (canPopFrames=false).");
        }
        ThreadReference thread = suspendedThread(threadId);
        String popped;
        String landedIn;
        try {
            StackFrame frame = thread.frame(frameIndex);
            popped = frame.location().method().name();
            thread.popFrames(frame);
            landedIn = thread.frame(0).location().method().name();
        } catch (Exception e) {
            throw new DebugException("POP_FRAME_FAILED",
                "Cannot pop frame " + frameIndex + ": " + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + " (a native frame cannot be popped, and neither can "
                    + "the bottom of the stack).");
        }

        Map<String, Object> mutation = recordMutation("pop_frame", new LinkedHashMap<>(Map.of(
            "popped", popped,
            "nowIn", landedIn,
            "frameIndex", frameIndex,
            "threadId", threadId,
            "note", "The thread is back at the call site, about to call " + popped + "() again. "
                + "ANY SIDE EFFECT IT ALREADY HAD IS STILL DONE — popping rewinds the stack, "
                + "not the world.")));
        suspended.put(thread.uniqueID(), thread);
        return mutation;
    }

    /**
     * "What if the code were different?" — replace a class's bytecode in the running JVM.
     *
     * <p>HotSpot allows method BODIES to change and nothing else. Adding or removing a
     * method or field, or changing the hierarchy, is refused — and we pass that refusal on
     * plainly instead of dressing it up as an internal error.</p>
     */
    public Map<String, Object> redefine(String className, byte[] bytecode) throws DebugException {
        requireLive();
        if (!vm.canRedefineClasses()) {
            throw new DebugException("CAPABILITY_ABSENT",
                "This JVM cannot redefine classes (canRedefineClasses=false).");
        }
        // Refuse if the name is ambiguous: redefining an arbitrary one of several same-named
        // classes (ordinary in OSGi — and JATS is OSGi) can patch a class that is not even
        // the one running, while reporting success.
        ReferenceType type = resolveUniqueType(className,
            "Redefining " + className + " (hot-swapping its code)");
        try {
            vm.redefineClasses(Map.of(type, bytecode));
        } catch (UnsupportedOperationException e) {
            throw new DebugException("REDEFINE_SCHEMA_CHANGE_UNSUPPORTED",
                "This JVM will only accept changed method BODIES. The new bytecode changes "
                    + className + "'s shape — an added or removed method or field, a changed "
                    + "signature, or a changed hierarchy" + (vm.canUnrestrictedlyRedefineClasses()
                        ? "" : " — and this JVM cannot do that (canUnrestrictedlyRedefineClasses"
                            + "=false)") + ". Restart the target with the new code instead.");
        } catch (NoClassDefFoundError | VerifyError | ClassFormatError e) {
            throw new DebugException("REDEFINE_REJECTED",
                "The JVM rejected the new bytecode for " + className + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (is this the .class for that exact class?)");
        } catch (Exception e) {
            throw new DebugException("REDEFINE_FAILED",
                "Redefining " + className + " failed: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }

        // Redefinition invalidates the old locations, so our breakpoints must be re-installed
        // against the NEW class — otherwise they silently stop firing, which looks exactly
        // like "the bug went away".
        int reinstalled = reinstallBreakpointsFor(className);

        return recordMutation("redefine", new LinkedHashMap<>(Map.of(
            "class", className,
            "bytes", bytecode.length,
            "breakpointsReinstalled", reinstalled,
            "note", "Methods already ON THE STACK keep running their old code until they are "
                + "re-entered. The change takes effect on the next call.")));
    }

    private int reinstallBreakpointsFor(String className) {
        List<ReferenceType> types = vm.classesByName(className);
        if (types.isEmpty()) {
            return 0;
        }
        int reinstalled = 0;
        for (Bp bp : breakpoints.values()) {
            if (!className.equals(bp.className) || bp.internal) {
                continue;
            }
            try {
                if (bp.request != null) {
                    vm.eventRequestManager().deleteEventRequest(bp.request);
                    bp.request = null;
                }
                bp.bound = false;
                install(bp, types.get(0));
                reinstalled++;
            } catch (DebugException e) {
                bp.pendingReason = "could not be re-installed after the class was redefined: "
                    + e.getMessage();
                log.warn("re-installing {} after redefine failed: {}", bp.id, e.getMessage());
            }
        }
        return reinstalled;
    }

    // ------------------------------------------------------------ instances

    /**
     * How many live instances of a type exist, and a bounded page of them.
     *
     * <p>Above {@link #INSTANCES_CAP} we stop enumerating and get the EXACT count from a
     * heap histogram instead — because "100" when the truth is 4 million is not a count,
     * it is a bound wearing a count's clothes.</p>
     */
    public Map<String, Object> instances(String className, int offset, int limit, int depth)
            throws DebugException {
        requireLive();
        if (!vm.canGetInstanceInfo()) {
            throw new DebugException("CAPABILITY_ABSENT",
                "This JVM cannot report instances (canGetInstanceInfo=false).");
        }
        if (vm.classesByName(className).isEmpty()) {
            throw new DebugException("TYPE_NOT_LOADED",
                className + " is not loaded in the target JVM — so it has no instances "
                    + "there. (Not the same as having zero: it has never been used.)");
        }
        // Count instances of the ONE class you mean — two same-named classes in two bundles
        // have two separate instance populations, and reporting one as if it were the answer
        // is a wrong number stated with confidence.
        ReferenceType type = resolveUniqueType(className, "Counting instances of " + className);

        // One over the cap tells us whether the cap bit.
        List<ObjectReference> found = type.instances(INSTANCES_CAP + 1L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", type.name());

        boolean overCap = found.size() > INSTANCES_CAP;
        if (overCap) {
            Optional<Long> exact = HeapHistogram.countOf(pid, type.name());
            if (exact.isPresent()) {
                result.put("count", exact.get());
                result.put("countSource", "heap histogram (jcmd GC.class_histogram) — exact");
            } else {
                result.put("count", null);
                result.put("countAtLeast", INSTANCES_CAP + 1);
                result.put("countSource", "unknown — more than the live-enumeration cap of "
                    + INSTANCES_CAP + ", and the heap histogram was unavailable on this "
                    + "target. This is a FLOOR, not a count.");
            }
        } else {
            result.put("count", found.size());
            result.put("countSource", "live enumeration — exact");
        }

        List<ObjectReference> page = found.stream()
            .skip(Math.max(0, offset))
            .limit(Math.max(1, Math.min(limit, 50)))
            .toList();
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (ObjectReference instance : page) {
            rendered.add(JdiValues.expand(instance, depth,
                JdiValues.DEFAULT_MAX_ITEMS, JdiValues.DEFAULT_MAX_BYTES));
        }
        result.put("instances", rendered);
        result.put("offset", Math.max(0, offset));
        result.put("returned", rendered.size());
        result.put("enumerationCap", INSTANCES_CAP);
        if (overCap) {
            result.put("pagingLimited", true);
            result.put("note", "Only the first " + INSTANCES_CAP + " instances can be paged "
                + "through live; the COUNT above is exact but the page window is not the "
                + "whole population.");
        }
        return result;
    }

    /** For D7 (Stage 9) and the array/collection paths — the raw VM, deliberately exposed. */
    public VirtualMachine vm() {
        return vm;
    }

    /** The suspended thread by id, for the mutation actions of Stage 9. */
    public Optional<ThreadReference> suspendedThreadOrEmpty(long threadId) {
        return Optional.ofNullable(suspended.get(threadId));
    }

    /** Array length for a paged expansion, without re-walking the whole object. */
    public static int lengthOf(Value value) {
        return value instanceof ArrayReference array ? array.length() : -1;
    }

    /** Used by the type-code paths: the loaded class, or empty when it never loaded. */
    public Optional<ClassType> loadedClass(String className) {
        List<ReferenceType> types = vm.classesByName(className);
        return types.isEmpty() || !(types.get(0) instanceof ClassType classType)
            ? Optional.empty() : Optional.of(classType);
    }
}

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

    private static ThreadReference threadOf(Event event) {
        if (event instanceof LocatableEvent locatable) {
            return locatable.thread();
        }
        return null;
    }

    private void record(Bp bp, Event event, ThreadReference thread) {
        Map<String, Object> hit = new LinkedHashMap<>();
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
        if (bp.fieldName == null) {
            throw new DebugException("BREAKPOINT_NEEDS_FIELD",
                "A field watchpoint needs a field name.");
        }
        Field field = type.fieldByName(bp.fieldName);
        if (field == null) {
            throw new DebugException("BREAKPOINT_UNKNOWN_FIELD",
                type.name() + " has no field '" + bp.fieldName + "'.");
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
        ThreadReference thread = suspendedThread(threadId);

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
        suspendedThread(threadId);   // the frame must be stopped or there is nothing to read
        ThreadReference thread = suspended.get(threadId);
        try {
            Value result = new JdiEvaluator(thread, frameIndex).evaluate(expression);
            Map<String, Object> evaluated = new LinkedHashMap<>();
            evaluated.put("expression", expression);
            evaluated.put("value", JdiValues.expand(result));
            evaluated.put("summary", JdiValues.summary(result));
            return evaluated;
        } catch (JdiEvaluator.EvalException e) {
            throw new DebugException(e.code, e.getMessage());
        }
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
        thread.resume();
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
        List<ReferenceType> types = vm.classesByName(className);
        if (types.isEmpty()) {
            throw new DebugException("TYPE_NOT_LOADED",
                className + " is not loaded in the target JVM — so it has no instances "
                    + "there. (Not the same as having zero: it has never been used.)");
        }
        ReferenceType type = types.get(0);

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

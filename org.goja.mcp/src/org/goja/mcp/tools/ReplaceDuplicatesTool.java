package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.FindDuplicateCodeTool.MethodFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 14b — {@code replace_duplicates}: closes the
 * {@code find_duplicate_code} loop. Re-resolves a clone group by its stable
 * {@code groupId}, keeps the canonical method, and rewrites every other
 * same-type clone's body into a delegation call to the canonical. Clones in
 * other types are skipped (cross-type delegation isn't automatically safe)
 * and reported with reasons.
 *
 * <p>All rewrites land in ONE Change performed atomically through the
 * Sprint 14b apply contract — one diff, one undo handle.</p>
 */
public class ReplaceDuplicatesTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ReplaceDuplicatesTool.class);

    public ReplaceDuplicatesTool(Supplier<IJdtService> serviceSupplier,
                                 RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "replace_duplicates";
    }

    @Override
    public String getDescription() {
        return """
            Replace duplicate method bodies with delegation calls to a canonical method.

            Closes the find_duplicate_code loop: pass a groupId from its output;
            every clone declared in the SAME TYPE as the canonical method gets its
            body rewritten to delegate ("return canonical(args);"). Clones in other
            types are skipped and listed with reasons (cross-type delegation is not
            automatically safe).

            Applies directly (default) and returns { filesModified, diff,
            undoChangeId, summary, replaced, skipped }. Verify with
            compile_workspace; revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead.

            USAGE:
              1. find_duplicate_code(minTokens=...) — note a group's groupId
              2. replace_duplicates(cloneGroupId="<groupId>", minTokens=<same value>)
            groupIds are stable hashes of the clone shape — no session state; use
            the SAME minTokens/projectKey/crossProject as the detection call.

            Optional: canonicalMethodName picks which instance survives
            (default: first instance in file/line order).

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cloneGroupId", Map.of(
            "type", "string",
            "description", "groupId from find_duplicate_code output."));
        properties.put("canonicalMethodName", Map.of(
            "type", "string",
            "description", "Optional. Which clone survives as the delegation target "
                + "(default: first instance in file/line order)."));
        properties.put("minTokens", Map.of(
            "type", "integer",
            "description", "Must match the find_duplicate_code call (default 50)."));
        properties.put("crossProject", Map.of(
            "type", "boolean",
            "description", "Must match the find_duplicate_code call (default true)."));
        schema.put("properties", properties);
        schema.put("required", List.of("cloneGroupId"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        ToolResponse missing = requireParam(arguments, "cloneGroupId");
        if (missing != null) {
            return Preparation.fail(missing);
        }
        String cloneGroupId = getStringParam(arguments, "cloneGroupId");
        String canonicalMethodName = getStringParam(arguments, "canonicalMethodName");
        int minTokens = getIntParam(arguments, "minTokens", 50);
        boolean crossProject = getBooleanParam(arguments, "crossProject", true);

        // Same project scoping as find_duplicate_code.
        String projectKey = getStringParam(arguments, "projectKey");
        Collection<LoadedProject> projects;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                return Preparation.fail(ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use list_projects."));
            }
            projects = List.of(scoped.get());
        } else {
            projects = service.allProjects();
        }

        // Re-resolve the group statelessly: same detection, match by stable id.
        Map<String, List<MethodFingerprint>> pool =
            FindDuplicateCodeTool.collectPool(service, projects, minTokens);
        List<MethodFingerprint> bucket = null;
        for (Map.Entry<String, List<MethodFingerprint>> entry : pool.entrySet()) {
            if (entry.getValue().size() >= 2
                    && FindDuplicateCodeTool.groupIdOf(entry.getKey()).equals(cloneGroupId)) {
                bucket = entry.getValue();
                break;
            }
        }
        if (bucket == null) {
            return Preparation.fail(ToolResponse.invalidParameter("cloneGroupId",
                "No clone group with id '" + cloneGroupId + "' under the given parameters. "
                    + "Run find_duplicate_code with the SAME minTokens/projectKey/crossProject "
                    + "and use a groupId from its output (the code may also have changed since "
                    + "detection — ids hash the clone shape)."));
        }
        if (!crossProject) {
            log.debug("crossProject=false accepted for parity with detection; "
                + "grouping is shape-based either way");
        }

        bucket.sort(Comparator.comparing((MethodFingerprint fp) -> fp.filePath)
            .thenComparingInt(fp -> fp.line));

        // Pick the canonical instance.
        MethodFingerprint canonical;
        if (canonicalMethodName != null && !canonicalMethodName.isBlank()) {
            canonical = bucket.stream()
                .filter(fp -> canonicalMethodName.equals(fp.methodName))
                .findFirst()
                .orElse(null);
            if (canonical == null) {
                return Preparation.fail(ToolResponse.invalidParameter("canonicalMethodName",
                    "No instance named '" + canonicalMethodName + "' in group " + cloneGroupId));
            }
        } else {
            canonical = bucket.get(0);
        }

        String canonicalTypeHandle = canonical.method.getDeclaringType().getHandleIdentifier();
        boolean canonicalStatic = org.eclipse.jdt.core.Flags.isStatic(canonical.method.getFlags());

        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        List<Map<String, Object>> replaced = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (MethodFingerprint fp : bucket) {
            if (fp == canonical) {
                continue;
            }
            String typeHandle = fp.method.getDeclaringType().getHandleIdentifier();
            if (!typeHandle.equals(canonicalTypeHandle)) {
                skipped.add(skipEntry(fp, "declared in a different type ("
                    + fp.method.getDeclaringType().getElementName()
                    + ") — cross-type delegation is not automatically safe"));
                continue;
            }
            boolean cloneStatic = org.eclipse.jdt.core.Flags.isStatic(fp.method.getFlags());
            if (cloneStatic && !canonicalStatic) {
                skipped.add(skipEntry(fp,
                    "clone is static but the canonical method is not — a static body "
                        + "cannot delegate to an instance method"));
                continue;
            }

            TextEdit edit = delegationBodyEdit(fp.method, canonical);
            if (edit == null) {
                skipped.add(skipEntry(fp, "could not locate the method body to rewrite"));
                continue;
            }
            if (fp.method.getResource() instanceof IFile file) {
                editsByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(edit);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("methodName", fp.methodName);
                entry.put("filePath", fp.filePath);
                entry.put("line", fp.line);
                replaced.add(entry);
            } else {
                skipped.add(skipEntry(fp, "method resource is not a workspace file"));
            }
        }

        Map<String, Object> groupInfo = new LinkedHashMap<>();
        groupInfo.put("groupId", cloneGroupId);
        groupInfo.put("canonical", Map.of(
            "methodName", canonical.methodName,
            "filePath", canonical.filePath,
            "line", canonical.line));
        groupInfo.put("replaced", replaced);
        groupInfo.put("replacedCount", replaced.size());
        groupInfo.put("skipped", skipped);

        if (editsByFile.isEmpty()) {
            // Nothing safely replaceable — success no-op with the full report.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", false);
            data.putAll(groupInfo);
            return Preparation.fail(ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "Cross-type clones need manual extraction (e.g. move the canonical "
                        + "to a shared helper first)"))
                .build()));
        }

        Change change = ChangeEngine.fromFileEdits(
            "replace duplicates of " + canonical.methodName, editsByFile);
        String summary = "replace_duplicates: " + replaced.size() + " clone(s) delegated to "
            + canonical.methodName + (skipped.isEmpty() ? "" : " (" + skipped.size() + " skipped)");
        return Preparation.of(change, summary, groupInfo);
    }

    private static Map<String, Object> skipEntry(MethodFingerprint fp, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("methodName", fp.methodName);
        entry.put("filePath", fp.filePath);
        entry.put("line", fp.line);
        entry.put("reason", reason);
        return entry;
    }

    /**
     * Replace the clone method's body block with a delegation call to the
     * canonical method, preserving the clone's signature (callers keep
     * working — only the duplicated body disappears).
     */
    private static TextEdit delegationBodyEdit(IMethod clone, MethodFingerprint canonical)
            throws Exception {
        ISourceRange range = clone.getSourceRange();
        String methodSource = clone.getSource();
        if (range == null || methodSource == null) {
            return null;
        }
        int bodyOpenRel = methodSource.indexOf('{');
        int bodyCloseRel = methodSource.lastIndexOf('}');
        if (bodyOpenRel < 0 || bodyCloseRel <= bodyOpenRel) {
            return null; // abstract / native — nothing to rewrite
        }

        boolean isVoid = "V".equals(clone.getReturnType());
        String call = canonical.methodName + "("
            + String.join(", ", clone.getParameterNames()) + ");";
        String newBody = "{\n        " + (isVoid ? call : "return " + call) + "\n    }";

        int absoluteStart = range.getOffset() + bodyOpenRel;
        int length = bodyCloseRel - bodyOpenRel + 1;
        return new ReplaceEdit(absoluteStart, length, newBody);
    }
}

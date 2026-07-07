package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.core.workspace.StrictDiskSync;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.GetDiagnosticsTool;
import org.goja.mcp.tools.RenameSymbolTool;
import org.goja.mcp.tools.SearchSymbolsTool;
import org.goja.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21d (item B) — strict disk sync at the dispatch seam: external edits (agent
 * Edit tool, git, another editor) are visible to the very next tool call, with no
 * manual refresh. No opt-out exists; the unchanged-tree fast path is the only skip.
 */
class StrictDiskSyncDispatchTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private ToolRegistry registry;

    private void wire(JdtServiceImpl loaded) {
        service = loaded;
        registry = new ToolRegistry();
        registry.register(new SearchSymbolsTool(() -> service));
        registry.register(new GetDiagnosticsTool(() -> service));
        registry.register(new RenameSymbolTool(() -> service, new RefactoringChangeCache()));
        registry.setDiskSync(new StrictDiskSync(() -> service));
    }

    private Path projectRoot() {
        return service.allProjects().iterator().next().projectRoot();
    }

    private ToolResponse search(String query) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        return registry.callTool("search_symbols", args);
    }

    @Test
    @DisplayName("THE acceptance repro: a new file in a NEW package is found by the next search — no manual refresh")
    void external_new_file_in_new_package_is_found_by_the_next_search() throws Exception {
        wire(helper.loadProjectCopy("simple-maven"));

        Path fresh = projectRoot().resolve("src/main/java/com/brandnew/FreshDiskSync.java");
        Files.createDirectories(fresh.getParent());
        Files.writeString(fresh,
            "package com.brandnew;\npublic class FreshDiskSync {\n    public int fresh() { return 21; }\n}\n");

        ToolResponse r = search("FreshDiskSync");
        assertTrue(r.isSuccess(), "search must succeed; got: " + r.getError());
        assertTrue(String.valueOf(r.getData()).contains("FreshDiskSync"),
            "the very next tool call sees the externally added type");
    }

    @Test
    @DisplayName("a deleted file disappears from the next search")
    void external_delete_disappears_from_the_next_search() throws Exception {
        wire(helper.loadProjectCopy("simple-maven"));

        Path fresh = projectRoot().resolve("src/main/java/com/brandnew/Doomed.java");
        Files.createDirectories(fresh.getParent());
        Files.writeString(fresh, "package com.brandnew;\npublic class Doomed {\n}\n");
        assertTrue(String.valueOf(search("Doomed").getData()).contains("Doomed"),
            "precondition: the type is visible after the add");

        Files.delete(fresh);
        ToolResponse r = search("Doomed");
        assertTrue(r.isSuccess());
        assertFalse(String.valueOf(r.getData()).contains("com.brandnew"),
            "the deleted type is gone from the very next search");
    }

    @Test
    @DisplayName("an external file reaches the diagnostics MODEL WALK without a manual refresh")
    void external_file_reaches_the_diagnostics_walk() throws Exception {
        // 21d owns MODEL freshness: the walk must SEE the external file on the very
        // next call. (Whether reconcile reports its problems is a PRE-EXISTING
        // get_diagnostics trait — reconcile on non-working-copy CUs returns a null
        // AST and zero problems; the product's real error channel is
        // compile_workspace's marker sweep. Found by this test, filed for 22b.)
        wire(helper.loadProjectCopy("simple-maven"));

        ObjectNode args = mapper.createObjectNode();
        ToolResponse before = registry.callTool("get_diagnostics", args);
        assertTrue(before.isSuccess(), "got: " + before.getError());
        int filesBefore = filesChecked(before);

        Path fresh = projectRoot().resolve("src/main/java/com/brandnew/WalkMe.java");
        Files.createDirectories(fresh.getParent());
        Files.writeString(fresh, "package com.brandnew;\npublic class WalkMe {\n}\n");

        ToolResponse after = registry.callTool("get_diagnostics", mapper.createObjectNode());
        assertTrue(after.isSuccess(), "got: " + after.getError());
        assertEquals(filesBefore + 1, filesChecked(after),
            "the very next whole-project walk includes the externally added file");
    }

    @SuppressWarnings("unchecked")
    private static int filesChecked(ToolResponse r) {
        return ((Number) ((java.util.Map<String, Object>) r.getData()).get("filesChecked")).intValue();
    }

    @Test
    @DisplayName("refactor freshness: a rename right after an external edit computes on the reconciled model")
    void rename_after_external_edit_computes_on_the_reconciled_model() throws Exception {
        wire(helper.loadProjectCopy("simple-maven"));

        Path fresh = projectRoot().resolve("src/main/java/com/brandnew/RenameMe.java");
        Files.createDirectories(fresh.getParent());
        Files.writeString(fresh, "package com.brandnew;\npublic class RenameMe {\n}\n");

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fresh.toString());
        args.put("line", 1);
        args.put("column", 13);
        args.put("newName", "RenamedFreshly");
        ToolResponse r = registry.callTool("rename_symbol", args);
        assertTrue(r.isSuccess(),
            "rename of a just-written external file must compute on the reconciled model; got: " + r.getError());
        assertTrue(Files.readString(fresh).contains("RenamedFreshly"),
            "the rename was applied to the externally created source");
    }

    @Test
    @DisplayName("unchanged tree: the second sync does zero work (the only legitimate skip)")
    void unchanged_tree_second_sync_does_zero_work() throws Exception {
        wire(helper.loadProjectCopy("simple-maven"));
        StrictDiskSync sync = new StrictDiskSync(() -> service);

        StrictDiskSync.SyncReport first = sync.syncBeforeCall();
        assertEquals(1, first.newProjects(), "first pass reconciles the new root once");

        StrictDiskSync.SyncReport second = sync.syncBeforeCall();
        assertFalse(second.reconciled(), "no edit -> no work");
        assertEquals(0, second.refreshedFiles());
        assertEquals(0, second.builtProjects());
    }

    @Test
    @DisplayName("two-project workspace: edits in BOTH projects are reconciled by one pass")
    void two_project_workspace_edits_in_both_are_reconciled() throws Exception {
        wire(helper.loadWorkspaceCopy("pde-bundle-a", "pde-bundle-b"));
        StrictDiskSync sync = new StrictDiskSync(() -> service);
        assertEquals(2, sync.syncBeforeCall().newProjects(), "both roots primed");

        int i = 0;
        for (var lp : service.allProjects()) {
            Path f = lp.projectRoot().resolve("src/gojadisksync/Extra" + (i++) + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "package gojadisksync;\npublic class Extra" + (i - 1) + " {\n}\n");
        }
        StrictDiskSync.SyncReport report = sync.syncBeforeCall();
        assertEquals(2, report.refreshedFiles(), "one added file per project reconciled");
        assertEquals(2, report.builtProjects());
    }

    @Test
    @DisplayName("guard crash: the tool call proceeds (WARN-and-proceed, never a switch)")
    void guard_crash_warns_and_the_tool_call_proceeds() throws Exception {
        wire(helper.loadProjectCopy("simple-maven"));
        registry.setDiskSync(new StrictDiskSync(() -> service) {
            @Override
            public synchronized SyncReport syncBeforeCall() {
                throw new IllegalStateException("deliberate guard crash");
            }
        });

        ToolResponse r = search("App");
        assertTrue(r.isSuccess(), "a guard crash must never break the tool call");
    }

    @Test
    @DisplayName("concurrent calls do not race the guard (dispatch runs on a cached thread pool)")
    void concurrent_calls_do_not_race_the_guard() throws Exception {
        wire(helper.loadProjectCopy("simple-maven"));

        Path fresh = projectRoot().resolve("src/main/java/com/brandnew/Raced.java");
        Files.createDirectories(fresh.getParent());
        Files.writeString(fresh, "package com.brandnew;\npublic class Raced {\n}\n");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ToolResponse>> results = pool.invokeAll(List.of(
                () -> search("Raced"),
                () -> search("Raced")));
            for (Future<ToolResponse> f : results) {
                assertTrue(f.get().isSuccess(), "concurrent guarded calls both succeed");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

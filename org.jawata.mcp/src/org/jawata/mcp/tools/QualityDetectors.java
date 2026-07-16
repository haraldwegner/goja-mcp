package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.DetectorCatalog;
import org.jawata.mcp.models.ToolResponse;

import java.util.function.Supplier;

/**
 * Sprint 16b/D — builds the built-in Smell {@link DetectorCatalog} by adapting
 * the eight narrow quality analyzers as {@link Detector}s. Lives in
 * {@code org.jawata.mcp.tools} so it can reach the analyzers' package-private
 * {@link AbstractTool#executeWithService}. Sprint 17 (Fowler) / 20 (SOLID)
 * register additional detectors into the same catalog; the Smell front door
 * picks them up automatically.
 */
public final class QualityDetectors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QualityDetectors() {
    }

    /** A fresh catalog pre-registered with the eight built-in quality detectors. */
    public static DetectorCatalog builtins(Supplier<IJdtService> svc) {
        return new DetectorCatalog()
            .register(adapt("naming",
                "Java naming-convention violations (classes, methods, fields, constants).",
                new FindNamingViolationsTool(svc), false), "quality")
            .register(adapt("bugs",
                "Common bug patterns (null deref, == on objects, mutation in lambda, …).",
                new FindPossibleBugsTool(svc), false), "quality")
            .register(adapt("unused",
                "Unused private methods and fields.",
                new FindUnusedCodeTool(svc), false), "quality")
            .register(adapt("large_classes",
                "Classes exceeding maxMethods/maxFields/maxLines thresholds.",
                new FindLargeClassesTool(svc), false), "quality")
            .register(adapt("circular_deps",
                "Cyclic package or class dependencies.",
                new FindCircularDependenciesTool(svc), false), "quality")
            .register(adapt("reflection",
                "Class.forName / Method.invoke / Field.get usage sites.",
                new FindReflectionUsageTool(svc), false), "quality")
            .register(adapt("throws",
                "Methods declaring 'throws <query>' (query = exception FQN).",
                new FindThrowsDeclarationsTool(svc), true), "quality")
            .register(adapt("catches",
                "'catch (<query> …)' blocks (query = exception FQN).",
                new FindCatchBlocksTool(svc), true), "quality")
            // Sprint 23 (GATE-1 addition): lack of coverage evidence as a smell.
            .register(new org.jawata.mcp.tools.smell.CoverageLackDetector(), "quality")
            // Sprint 25 (D3a): undocumented public API as a smell (the doc ratchet).
            .register(new org.jawata.mcp.tools.smell.JavadocLackDetector(), "quality");
    }

    /**
     * Adapt a narrow analyzer to a {@link Detector}. {@code exceptionAlias} maps
     * the unified {@code query} param onto the narrow tool's {@code exceptionType}
     * (the throws/catches contract preserved from the original front door).
     */
    private static Detector adapt(String kind, String description, AbstractTool tool, boolean exceptionAlias) {
        return new Detector() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public ToolResponse detect(IJdtService service, JsonNode arguments) {
                return tool.executeWithService(service, exceptionAlias ? withExceptionTypeAlias(arguments) : arguments);
            }
        };
    }

    private static JsonNode withExceptionTypeAlias(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return arguments;
        }
        ObjectNode copy;
        if (arguments instanceof ObjectNode existing) {
            copy = existing.deepCopy();
        } else {
            copy = MAPPER.createObjectNode();
            arguments.fields().forEachRemaining(e -> copy.set(e.getKey(), e.getValue()));
        }
        if (!copy.has("exceptionType") && copy.has("query")) {
            copy.set("exceptionType", copy.get("query"));
        }
        return copy;
    }
}

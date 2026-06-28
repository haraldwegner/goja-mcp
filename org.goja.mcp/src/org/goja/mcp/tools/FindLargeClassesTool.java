package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find types exceeding configurable member count or line count thresholds.
 * Useful for identifying classes that should be refactored into smaller units.
 */
public class FindLargeClassesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindLargeClassesTool.class);

    public FindLargeClassesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_large_classes";
    }

    @Override
    public String getDescription() {
        return """
            Find classes that exceed size thresholds.

            USAGE: find_large_classes(maxMethods=20, maxFields=10, maxLines=300)
            OUTPUT: List of classes exceeding any threshold with their metrics

            Default thresholds:
            - maxMethods: 20 methods
            - maxFields: 10 fields
            - maxLines: 300 lines

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> maxMethods = new LinkedHashMap<>();
        maxMethods.put("type", "integer");
        maxMethods.put("description", "Maximum methods before flagging (default 20)");
        properties.put("maxMethods", maxMethods);

        Map<String, Object> maxFields = new LinkedHashMap<>();
        maxFields.put("type", "integer");
        maxFields.put("description", "Maximum fields before flagging (default 10)");
        properties.put("maxFields", maxFields);

        Map<String, Object> maxLines = new LinkedHashMap<>();
        maxLines.put("type", "integer");
        maxLines.put("description", "Maximum lines before flagging (default 300)");
        properties.put("maxLines", maxLines);

        schema.put("properties", properties);
        schema.put("required", List.of());

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int methodThreshold = getIntParam(arguments, "maxMethods", 20);
        int fieldThreshold = getIntParam(arguments, "maxFields", 10);
        int lineThreshold = getIntParam(arguments, "maxLines", 300);

        try {
            List<Path> allFiles = service.getAllJavaFiles();
            List<Map<String, Object>> largeClasses = new ArrayList<>();
            int totalClassesScanned = 0;

            for (Path filePath : allFiles) {
                ICompilationUnit cu = service.getCompilationUnit(filePath);
                if (cu == null) continue;

                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(true);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);

                for (Object type : ast.types()) {
                    if (type instanceof TypeDeclaration typeDecl) {
                        totalClassesScanned++;

                        int methodCount = typeDecl.getMethods().length;
                        int fieldCount = typeDecl.getFields().length;
                        int startLine = ast.getLineNumber(typeDecl.getStartPosition());
                        int endLine = ast.getLineNumber(typeDecl.getStartPosition() + typeDecl.getLength());
                        int lineCount = endLine - startLine + 1;

                        List<String> violations = new ArrayList<>();
                        if (methodCount > methodThreshold) violations.add("methods: " + methodCount + " > " + methodThreshold);
                        if (fieldCount > fieldThreshold) violations.add("fields: " + fieldCount + " > " + fieldThreshold);
                        if (lineCount > lineThreshold) violations.add("lines: " + lineCount + " > " + lineThreshold);

                        if (!violations.isEmpty()) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("file", service.getPathUtils().formatPath(filePath));
                            entry.put("typeName", typeDecl.getName().getIdentifier());
                            entry.put("methodCount", methodCount);
                            entry.put("fieldCount", fieldCount);
                            entry.put("lineCount", lineCount);
                            entry.put("violations", violations);
                            largeClasses.add(entry);
                        }
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalClassesScanned", totalClassesScanned);
            data.put("totalViolations", largeClasses.size());
            data.put("thresholds", Map.of("maxMethods", methodThreshold, "maxFields", fieldThreshold, "maxLines", lineThreshold));
            data.put("largeClasses", largeClasses);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(largeClasses.size())
                .returnedCount(largeClasses.size())
                .suggestedNextTools(List.of(
                    "get_complexity_metrics for detailed method-level metrics",
                    "analyze_type for full type analysis"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}

package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.8.1 dogfood fix (found live 2026-07-11 on the v2.8.0 release day): the
 * find_references front door DOCUMENTED a {@code query} parameter no delegate
 * consumed, while omitting the {@code symbol}/{@code scope} FQN form the
 * delegates support since v1.8.0. Contract now: the schema declares
 * {@code symbol} + {@code scope}, and {@code query} is honored as an alias
 * for {@code symbol} (back-compat with the old published description).
 */
class FindRefsQueryAliasTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;
    private FindRefsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        mapper = new ObjectMapper();
        tool = new FindRefsTool(() -> service);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("schema declares the FQN form: symbol + scope properties present")
    void schemaDeclaresSymbolAndScope() {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        assertTrue(props.containsKey("symbol"), "front-door schema must declare 'symbol'");
        assertTrue(props.containsKey("scope"), "front-door schema must declare 'scope'");
    }

    @Test
    @DisplayName("kind=references: query is honored as a symbol alias")
    void queryAliasResolvesForReferences() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "references");
        args.put("query", "com.example.HelloWorld");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "query alias must resolve; got: " + r.getError());
    }

    @Test
    @DisplayName("kind=method_references: query FQN works and equals the symbol form")
    void queryAliasParityWithSymbol() {
        ObjectNode viaQuery = mapper.createObjectNode();
        viaQuery.put("kind", "method_references");
        viaQuery.put("query", "com.example.HelloWorld#getGreeting");
        ToolResponse q = tool.execute(viaQuery);
        assertTrue(q.isSuccess(), () -> "query form must resolve; got: " + q.getError());

        ObjectNode viaSymbol = mapper.createObjectNode();
        viaSymbol.put("kind", "method_references");
        viaSymbol.put("symbol", "com.example.HelloWorld#getGreeting");
        ToolResponse s = tool.execute(viaSymbol);
        assertTrue(s.isSuccess(), () -> "symbol form must resolve; got: " + s.getError());

        assertEquals(mapper.valueToTree(s.getData()), mapper.valueToTree(q.getData()),
            "query and symbol forms must return identical payloads");
    }

    @Test
    @DisplayName("explicit symbol wins over a conflicting query")
    void symbolWinsOverQuery() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "references");
        args.put("symbol", "com.example.HelloWorld");
        args.put("query", "com.example.DoesNotExist");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "symbol must win; got: " + r.getError());
    }
}

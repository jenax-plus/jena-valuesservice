/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aksw.jena.valuesservice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.engine.iterator.QueryIterCommonParent;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.service.ServiceExecutorRegistry;
import org.apache.jena.sparql.util.Context;
import org.junit.jupiter.api.Test;

class ValuesServiceTest {
    @org.junit.jupiter.api.BeforeAll
    static void initJena() {
        org.apache.jena.sys.JenaSystem.init();
    }

    private static Dataset data(String ttl) {
        return DatasetFactory.create(RDFParser.fromString(ttl, Lang.TTL).toModel());
    }

    private static List<Binding> rows(Dataset dataset, String query, Context context) {
        try (QueryExecution exec = QueryExecution.dataset(dataset).query(query).context(context).build()) {
            List<Binding> result = new ArrayList<>();
            ResultSet rs = exec.execSelect();
            while (rs.hasNext()) result.add(rs.nextBinding());
            return result;
        }
    }

    private static List<Binding> rows(String body) {
        return rows(DatasetFactory.create(), "SELECT * WHERE { " + body + " }", new Context());
    }

    // Serialize and reparse at each simulated endpoint: no algebra or input binding leaks
    // across the boundary. This also exercises OpAsQuery, used by Jena's HTTP transport.
    private static Context endpoints(Map<String, Dataset> datasets, List<String> sent) {
        Context context = new Context();
        ServiceExecutorRegistry registry = new ServiceExecutorRegistry();
        registry.add((execute, original, binding, ec) -> {
            Dataset remote = datasets.get(execute.getService().getURI());
            if (remote == null) throw new QueryExecException("Unexpected endpoint " + execute.getService());
            String query = OpAsQuery.asQuery(execute.getSubOp()).toString();
            sent.add(query);
            ExecutionContext remoteContext = ExecutionContext.create(remote.asDatasetGraph(), context);
            return new QueryIterCommonParent(QC.execute(Algebra.compile(QueryFactory.create(query)),
                    BindingFactory.empty(), remoteContext), binding, ec);
        });
        JenaPluginValuesService.init(registry);
        ServiceExecutorRegistry.set(context, registry);
        return context;
    }

    @Test void automaticallyRegisteredAndIdempotent() {
        assertEquals(1, rows("SERVICE <values:> { VALUES ?x { 1 } }").size());
        ServiceExecutorRegistry reg = new ServiceExecutorRegistry();
        JenaPluginValuesService.init(reg);
        JenaPluginValuesService.init(reg);
        assertEquals(1, reg.getSingleChain().size());
    }

    @Test void tablePreservesDuplicatesAndUnboundCells() {
        List<Binding> result = rows("SERVICE <values:0> { VALUES (?x ?y) { (1 UNDEF) (1 UNDEF) (2 3) } }");
        assertEquals(3, result.size());
        assertNull(result.get(0).get(Var.alloc("y")));
        assertEquals(result.get(0), result.get(1));
    }

    @Test void emptyAndUnitTables() {
        assertTrue(rows("SERVICE <values:> { VALUES ?x {} }").isEmpty());
        assertEquals(1, rows("SERVICE <values:> {}").size());
    }

    @Test void projectionAndIncomingBinding() {
        List<Binding> result = rows("VALUES ?x { 1 } SERVICE <values:> { SELECT ?x WHERE { VALUES (?x ?hidden) { (1 9) (2 8) } } }");
        assertEquals(1, result.size());
        assertNotNull(result.get(0).get(Var.alloc("x")));
        assertNull(result.get(0).get(Var.alloc("hidden")));
    }

    @Test void currentDatasetAndActiveGraph() {
        Dataset dataset = data("<urn:default> <urn:p> 1 .");
        dataset.addNamedModel("urn:g", RDFParser.fromString("<urn:named> <urn:p> 2 .", Lang.TTL).toModel());
        List<Binding> result = rows(dataset, "SELECT * { GRAPH <urn:g> { SERVICE <values:> { ?s <urn:p> ?o } } }", new Context());
        assertEquals(1, result.size());
        assertEquals(NodeFactory.createURI("urn:named"), result.get(0).get(Var.alloc("s")));
    }

    @Test void injectLocalTableAndSerializeIt() {
        Dataset local = data("<urn:local> <urn:p> 1 .");
        Dataset remote = data("<urn:remote> <urn:p> 2 .");
        List<String> sent = new ArrayList<>();
        Context context = endpoints(Map.of("urn:remote", remote), sent);
        List<Binding> result = rows(local, "SELECT * { SERVICE <urn:remote> { SERVICE <values:> { ?s <urn:p> ?o } } }", context);
        assertEquals(1, result.size());
        assertEquals(NodeFactory.createURI("urn:local"), result.get(0).get(Var.alloc("s")));
        assertTrue(sent.get(0).contains("VALUES"));
        assertFalse(sent.get(0).contains("values:"));
    }

    @Test void deferToRemoteDataset() {
        List<String> sent = new ArrayList<>();
        Context context = endpoints(Map.of("urn:remote", data("<urn:remote> <urn:p> 2 .")), sent);
        List<Binding> result = rows(data("<urn:local> <urn:p> 1 ."),
                "SELECT * { SERVICE <urn:remote> { SERVICE <values:1> { ?s <urn:p> ?o } } }", context);
        assertEquals(NodeFactory.createURI("urn:remote"), result.get(0).get(Var.alloc("s")));
        assertTrue(sent.get(0).contains("values:0"));
    }

    @Test void decrementOncePerBoundaryAcrossNestedServices() {
        for (int counter = 0; counter <= 2; counter++) {
            List<String> sent = new ArrayList<>();
            Context context = endpoints(Map.of("urn:a", data("<urn:a> <urn:p> 1 ."),
                    "urn:b", data("<urn:b> <urn:p> 2 .")), sent);
            List<Binding> result = rows(data("<urn:local> <urn:p> 0 ."),
                    "SELECT * { SERVICE <urn:a> { SERVICE <urn:b> { SERVICE <values:" + counter
                    + "> { ?s <urn:p> ?o } } } }", context);
            assertEquals(1, result.size());
            assertEquals(NodeFactory.createURI(counter == 0 ? "urn:local" : counter == 1 ? "urn:a" : "urn:b"),
                    result.get(0).get(Var.alloc("s")));
        }
    }

    @Test void deferredWorkloadIsOpaqueUntilDestination() {
        List<String> sent = new ArrayList<>();
        Context context = endpoints(Map.of("urn:remote", data("<urn:remote> <urn:p> 1 .")), sent);
        List<Binding> result = rows(DatasetFactory.create(),
                "SELECT * { SERVICE <urn:remote> { SERVICE <values:1> { SERVICE <values:> { ?s <urn:p> ?o } } } }", context);
        assertEquals(1, result.size());
        assertTrue(sent.get(0).contains("values:0"));
        assertTrue(sent.get(0).contains("<values:>"));
    }

    @Test void existsPatternsAreRewritten() {
        List<String> sent = new ArrayList<>();
        Context context = endpoints(Map.of("urn:remote", DatasetFactory.create()), sent);
        List<Binding> result = rows(data("<urn:local> <urn:p> 1 ."),
                "SELECT * { SERVICE <urn:remote> { VALUES ?x { 1 } FILTER EXISTS { SERVICE <values:> { ?s <urn:p> ?o } } } }", context);
        assertEquals(1, result.size());
        assertFalse(sent.get(0).contains("values:"));
    }

    @Test void invalidCountersAndDepthFailEvenWhenSilent() {
        for (String endpoint : List.of("values:-1", "values:abc", "values:2147483648", "values:1")) {
            assertThrows(QueryExecException.class, () -> rows("SERVICE SILENT <" + endpoint + "> {}"));
        }
        List<String> sent = new ArrayList<>();
        Context context = endpoints(Map.of(), sent);
        assertThrows(QueryExecException.class, () -> rows(DatasetFactory.create(),
                "SELECT * { SERVICE SILENT <urn:remote> { SERVICE <values:2> {} } }", context));
        assertTrue(sent.isEmpty());
    }

    @Test void blankNodesRejectedAndSilentWorkloadFailureReturnsIdentity() {
        Dataset dataset = data("[] <urn:p> 1 .");
        assertThrows(QueryExecException.class, () -> rows(dataset,
                "SELECT * { SERVICE <values:> { ?s <urn:p> ?o } }", new Context()));
        assertEquals(1, rows(dataset,
                "SELECT * { SERVICE SILENT <values:> { ?s <urn:p> ?o } }", new Context()).size());
    }

    @Test void serviceVariableCanSelectMarker() {
        Var endpoint = Var.alloc("endpoint");
        Binding binding = BindingBuilder.create().add(endpoint, NodeFactory.createURI("values:")).build();
        OpService original = new OpService(endpoint, OpTable.unit(), false);
        OpService execute = new OpService(NodeFactory.createURI("values:"), OpTable.unit(), false);
        ExecutionContext ec = ExecutionContext.create(DatasetFactory.create().asDatasetGraph());
        QueryIterator result = ValuesServiceExecutor.INSTANCE.createExecution(execute, original, binding, ec,
                (a, b, c, d) -> { throw new AssertionError("Marker must not be delegated"); });
        try { assertTrue(result.hasNext()); } finally { result.close(); }
    }

    @Test void ordinaryServiceDelegatesUnchanged() {
        OpService service = new OpService(NodeFactory.createURI("urn:remote"), OpTable.unit(), false);
        ExecutionContext ec = ExecutionContext.create(DatasetFactory.create().asDatasetGraph());
        QueryIterator result = ValuesServiceExecutor.INSTANCE.createExecution(service, service,
                BindingFactory.empty(), ec, (execute, original, binding, context) -> {
                    assertSame(service, execute);
                    assertSame(service, original);
                    return QC.execute(OpTable.unit(), binding, context);
                });
        try { assertTrue(result.hasNext()); } finally { result.close(); }
    }
    @Test void materializationKeepsInternalScopeVariables() {
        Var scoped = Var.alloc("/x");
        Op pattern = Algebra.compile(QueryFactory.create("SELECT ?x { VALUES ?x { 1 2 } }"));
        pattern = Rename.renameVar(pattern, Var.alloc("x"), scoped);
        OpService marker = new OpService(NodeFactory.createURI("values:"), pattern, false);
        ExecutionContext ec = ExecutionContext.create(DatasetFactory.create().asDatasetGraph());
        QueryIterator iterator = ValuesServiceExecutor.INSTANCE.createExecution(marker, marker,
                BindingFactory.empty(), ec, (a,b,c,d) -> { throw new AssertionError(); });
        try {
            Binding result = iterator.nextBinding();
            assertNotNull(result.get(scoped));
            assertNull(result.get(Var.alloc("x")));
        } finally { iterator.close(); }
    }

    @Test void incomingBindingDoesNotCaptureHiddenSubqueryVariable() {
        Var scoped = Var.alloc("/x");
        Op pattern = Algebra.compile(QueryFactory.create("SELECT ?s { ?s <urn:p> ?x }"));
        pattern = Rename.renameVar(pattern, Var.alloc("x"), scoped);
        OpService marker = new OpService(NodeFactory.createURI("values:"), pattern, false);
        Binding binding = BindingBuilder.create().add(Var.alloc("x"), NodeFactory.createLiteralString("outer")).build();
        ExecutionContext ec = ExecutionContext.create(data("<urn:local> <urn:p> 1 .").asDatasetGraph());
        QueryIterator iterator = ValuesServiceExecutor.INSTANCE.createExecution(marker, marker, binding, ec,
                (a,b,c,d) -> { throw new AssertionError(); });
        try {
            Binding result = iterator.nextBinding();
            assertEquals(NodeFactory.createURI("urn:local"), result.get(Var.alloc("s")));
            assertEquals(binding.get(Var.alloc("x")), result.get(Var.alloc("x")));
            assertNull(result.get(scoped));
        } finally { iterator.close(); }
    }

    @Test void cancellationIsNotSilenced() {
        Context context = new Context();
        context.set(org.apache.jena.sparql.ARQConstants.symCancelQuery,
                new java.util.concurrent.atomic.AtomicBoolean(true));
        ExecutionContext ec = ExecutionContext.create(DatasetFactory.create().asDatasetGraph(), context);
        OpService marker = new OpService(NodeFactory.createURI("values:"), OpTable.unit(), true);
        assertThrows(QueryCancelledException.class, () -> ValuesServiceExecutor.INSTANCE.createExecution(
                marker, marker, BindingFactory.empty(), ec, (a,b,c,d) -> { throw new AssertionError(); }));
    }

    @Test void httpTransportAndScopeMapping() throws Exception {
        Dataset remote = data("<urn:remote> <urn:p> 2 .");
        var sent = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sparql", exchange -> {
            try {
                String raw = exchange.getRequestURI().getRawQuery();
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                String query = null;
                if ("POST".equals(exchange.getRequestMethod())) {
                    raw = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (type != null && type.startsWith("application/sparql-query")) query = raw;
                }
                if (query == null) {
                    for (String pair : raw.split("&")) {
                        String[] parts = pair.split("=", 2);
                        if (parts[0].equals("query")) query = java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                sent.add(query);
                var output = new java.io.ByteArrayOutputStream();
                try (QueryExecution exec = QueryExecution.create(query, remote)) {
                    ResultSetFormatter.outputAsJSON(output, exec.execSelect());
                }
                byte[] bytes = output.toByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/sparql-results+json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
                byte[] bytes = e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/sparql";
            Dataset local = data("<urn:local> <urn:p> 1 .");
            for (int counter = 0; counter <= 1; counter++) {
                String query = "SELECT ?s WHERE { SERVICE <" + endpoint + "> { SERVICE <values:" + counter
                        + "> { SELECT ?s WHERE { ?s <urn:p> ?hidden } } } }";
                List<Binding> result = rows(local, query, new Context());
                assertEquals(1, result.size());
                assertEquals(NodeFactory.createURI(counter == 0 ? "urn:local" : "urn:remote"),
                        result.get(0).get(Var.alloc("s")));
                assertNull(result.get(0).get(Var.alloc("hidden")));
            }
            // The caller expects an internal scope name. Service.exec must unrename it
            // on the wire and map the remote result back before returning it here.
            Var scoped = Var.alloc("/x");
            Op body = Algebra.compile(QueryFactory.create("SELECT ?x { SERVICE <values:> { VALUES ?x { 7 } } }"));
            body = Rename.renameVar(body, Var.alloc("x"), scoped);
            OpService service = new OpService(NodeFactory.createURI(endpoint), body, false);
            ExecutionContext ec = ExecutionContext.create(local.asDatasetGraph());
            QueryIterator iterator = ValuesServiceExecutor.INSTANCE.createExecution(service, service,
                    BindingFactory.empty(), ec, ServiceExecutorRegistry.httpService);
            try {
                Binding result = iterator.nextBinding();
                assertNotNull(result.get(scoped));
                assertNull(result.get(Var.alloc("x")));
                assertFalse(iterator.hasNext());
            } finally { iterator.close(); }
            List<Binding> exists = rows(local, "SELECT * { SERVICE <" + endpoint
                    + "> { VALUES ?x { 1 } FILTER EXISTS { SERVICE <values:> { ?s <urn:p> ?o } } } }", new Context());
            assertEquals(1, exists.size());
            List<Binding> notExists = rows(local, "SELECT * { SERVICE <" + endpoint
                    + "> { VALUES ?x { 1 } FILTER NOT EXISTS { SERVICE <values:> { ?s <urn:p> ?o } } } }", new Context());
            assertTrue(notExists.isEmpty());
            assertTrue(sent.get(0).contains("VALUES"));
            assertTrue(sent.get(1).contains("values:0"));
            assertFalse(sent.get(2).contains("?/x"));
        } finally { server.stop(0); }
    }

}

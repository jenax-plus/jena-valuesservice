/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package org.aksw.jena.valuesservice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.service.ServiceExecutorRegistry;
import org.apache.jena.sparql.util.*;
import org.apache.jena.tdb2.*;
import org.junit.jupiter.api.*;

class UnionDefaultGraphTest {
    private static final Symbol CURRENT = Symbol.create("http://jena.apache.org/TDB#unionDefaultGraph");
    private static final Symbol LEGACY = Symbol.create("http://jena.hpl.hp.com/TDB#unionDefaultGraph");
    private static final String SELECT = "SELECT DISTINCT ?s { ?s ?p ?o } LIMIT 1";

    @BeforeAll static void init() { org.apache.jena.sys.JenaSystem.init(); }

    private Dataset dataset() {
        Dataset dataset = TDB2Factory.createDataset();
        dataset.begin(ReadWrite.WRITE);
        dataset.asDatasetGraph().add(new Quad(NodeFactory.createURI("urn:g1"),
                NodeFactory.createURI("urn:s1"), NodeFactory.createURI("urn:p"), NodeFactory.createURI("urn:o")));
        dataset.asDatasetGraph().add(new Quad(NodeFactory.createURI("urn:g2"),
                NodeFactory.createURI("urn:s2"), NodeFactory.createURI("urn:p"), NodeFactory.createURI("urn:o")));
        dataset.commit();
        dataset.end();
        return dataset;
    }

    private List<Binding> rows(Dataset dataset, String query, Context context) {
        try (QueryExecution exec = QueryExecution.dataset(dataset).query(query).context(context).build()) {
            List<Binding> rows = new ArrayList<>();
            ResultSet rs = exec.execSelect();
            while (rs.hasNext()) rows.add(rs.nextBinding());
            return rows;
        }
    }

    @Test void checkMatchesTdbSymbolsWithoutRuntimeDependency() {
        assertEquals(CURRENT, TDB2.symUnionDefaultGraph2);
        assertEquals(LEGACY, TDB2.symUnionDefaultGraph1);
        for (Symbol flag : List.of(CURRENT, LEGACY)) {
            Context context = new Context();
            assertFalse(ValuesServiceExecutor.isUnionDefaultGraph(context));
            context.set(flag, true);
            assertTrue(ValuesServiceExecutor.isUnionDefaultGraph(context));
            context.set(flag, false);
            assertFalse(ValuesServiceExecutor.isUnionDefaultGraph(context));
        }
    }

    @Test void directMarkerUsesTdbUnionForEitherFlag() {
        Dataset dataset = dataset();
        dataset.begin(ReadWrite.READ);
        try {
            for (Symbol flag : List.of(CURRENT, LEGACY)) {
                dataset.getContext().set(flag, true);
                Context request = dataset.getContext().copy();
                assertEquals(1, rows(dataset, SELECT, request).size());
                assertEquals(1, rows(dataset, "SELECT * { SERVICE <values:> { " + SELECT + " } }", request).size());
                assertTrue(dataset.asDatasetGraph().getDefaultGraph().isEmpty());
                dataset.getContext().remove(flag);
            }
        } finally { dataset.end(); dataset.close(); }
    }

    @Test void outgoingRequestContainsNonemptyTable() {
        Dataset dataset = dataset();
        dataset.begin(ReadWrite.READ);
        try {
            Context request = dataset.getContext().copy();
            request.set(CURRENT, true);
            List<OpTable> tables = new ArrayList<>();
            ServiceExecutorRegistry registry = ServiceExecutorRegistry.get().copy();
            registry.add((execute, original, binding, ec) -> {
                if (!execute.getService().hasURI("urn:remote")) return null;
                OpWalker.walk(execute.getSubOp(), new OpVisitorBase() {
                    @Override public void visit(OpTable table) { tables.add(table); }
                });
                // Emulate the remote endpoint without contacting DBpedia.
                return QC.execute(execute.getSubOp(), binding,
                        ExecutionContext.create(DatasetGraphFactory.create(), ec.getContext()));
            });
            JenaPluginValuesService.init(registry);
            // add() prepends, so restore the values executor ahead of the remote stub.
            registry.removeSingleLink(ValuesServiceExecutor.INSTANCE);
            registry.addSingleLink(ValuesServiceExecutor.INSTANCE);
            ServiceExecutorRegistry.set(request, registry);
            assertEquals(1, rows(dataset,
                    "SELECT * { SERVICE <urn:remote> { SERVICE <values:> { " + SELECT + " } } }", request).size());
            assertEquals(1, tables.size());
            assertEquals(1, tables.get(0).getTable().size());
            assertEquals(List.of(Var.alloc("s")), tables.get(0).getTable().getVars());
        } finally { dataset.end(); dataset.close(); }
    }

    @Test void explicitGraphScopesArePreserved() {
        Dataset dataset = dataset();
        dataset.begin(ReadWrite.READ);
        try {
            Context request = dataset.getContext().copy();
            request.set(CURRENT, true);
            for (String pattern : List.of("SERVICE <values:> { GRAPH <urn:g1> { ?s ?p ?o } }")) {
                List<Binding> result = rows(dataset, "SELECT * { " + pattern + " }", request);
                assertEquals(1, result.size(), pattern);
                assertEquals(NodeFactory.createURI("urn:s1"), result.get(0).get(Var.alloc("s")));
            }
        } finally { dataset.end(); dataset.close(); }
    }

    @Test void namedActiveGraphIsNotReplacedByUnion() {
        Dataset dataset = dataset();
        dataset.begin(ReadWrite.READ);
        try {
            DatasetGraph dsg = dataset.asDatasetGraph();
            Context context = dataset.getContext().copy();
            context.set(CURRENT, true);
            ExecutionContext ec = ExecutionContext.create(dsg, dsg.getGraph(NodeFactory.createURI("urn:g1")), context);
            OpService marker = new OpService(NodeFactory.createURI("values:"),
                    Algebra.compile(QueryFactory.create(SELECT)), false);
            QueryIterator result = ValuesServiceExecutor.INSTANCE.createExecution(marker, marker,
                    BindingFactory.empty(), ec, (a,b,c,d) -> { throw new AssertionError(); });
            try {
                assertEquals(NodeFactory.createURI("urn:s1"), result.nextBinding().get(Var.alloc("s")));
                assertFalse(result.hasNext());
            } finally { result.close(); }
        } finally { dataset.end(); dataset.close(); }
    }

    @Test void requestFalseOverridesDatasetDefault() {
        Dataset dataset = dataset();
        dataset.getContext().set(CURRENT, true);
        dataset.begin(ReadWrite.READ);
        try {
            Context request = dataset.getContext().copy();
            request.set(CURRENT, false);
            assertTrue(rows(dataset, "SELECT * { SERVICE <values:> { " + SELECT + " } }", request).isEmpty());
            assertTrue(dataset.getContext().isTrue(CURRENT));
        } finally { dataset.end(); dataset.close(); }
    }

    @Test void copiedContextPreservesFactoryGraphDatasetAndCancelSignal() {
        DatasetGraph dataset = DatasetGraphFactory.create();
        Context context = new Context();
        Symbol marker = Symbol.create("urn:materialization-test");
        var cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        context.set(org.apache.jena.sparql.ARQConstants.symCancelQuery, cancel);
        var graph = dataset.getDefaultGraph();
        var factory = new org.apache.jena.sparql.engine.main.OpExecutorFactory() {
            @Override public org.apache.jena.sparql.engine.main.OpExecutor create(ExecutionContext ec) {
                assertSame(dataset, ec.getDataset());
                assertSame(graph, ec.getActiveGraph());
                assertSame(cancel, ec.getCancelSignal());
                if (ec.getContext() != context) ec.getContext().set(marker, true);
                return org.apache.jena.sparql.engine.main.OpExecutor.stdFactory.create(ec);
            }
        };
        QC.setFactory(context, factory);
        ExecutionContext parent = ExecutionContext.create(dataset, graph, context);
        OpService service = new OpService(NodeFactory.createURI("values:"), OpTable.unit(), false);
        QueryIterator result = ValuesServiceExecutor.INSTANCE.createExecution(service, service,
                BindingFactory.empty(), parent, (a,b,c,d) -> { throw new AssertionError(); });
        try { assertTrue(result.hasNext()); } finally { result.close(); }
        assertFalse(context.isDefined(marker));
    }
}

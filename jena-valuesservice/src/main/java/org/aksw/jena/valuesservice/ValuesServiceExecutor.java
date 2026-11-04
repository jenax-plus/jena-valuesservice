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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QueryExecException;
import org.apache.jena.query.QueryCancelledException;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpSequence;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.algebra.table.TableData;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.expr.ExprTransformCopy;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.service.single.ChainingServiceExecutor;
import org.apache.jena.sparql.service.single.ServiceExecutor;

/** Materializes values: SERVICE patterns before delegating outgoing SERVICE calls. */
public class ValuesServiceExecutor implements ChainingServiceExecutor {
    /** Shared stateless executor, also used by automatic registration. */
    public static final ValuesServiceExecutor INSTANCE = new ValuesServiceExecutor();

    @Override
    public QueryIterator createExecution(OpService opExecute, OpService opOriginal,
            Binding binding, ExecutionContext execCxt, ServiceExecutor chain) {
        // Validate before executing any workload, including under SERVICE SILENT.
        validate(opOriginal, 0, binding);
        Integer count = counter(opExecute.getService());
        if (count != null) {
            return QC.execute(materialize(opOriginal, binding, execCxt), binding, execCxt);
        }
        Op body = rewrite(opOriginal.getSubOp(), binding, execCxt);
        if (body == opOriginal.getSubOp()) {
            return chain.createExecution(opExecute, opOriginal, binding, execCxt);
        }
        // Keep internal scope-renamed variables intact: Service.exec reverses them for
        // transport and maps results back. Drop cached syntax so downstream
        // serializers use the new algebra.
        OpService original = new OpService(opOriginal.getService(), body, opOriginal.getSilent());
        OpService execute = (OpService)Substitute.substitute(original, binding);
        return chain.createExecution(execute, original, binding, execCxt);
    }

    private static Integer counter(Node endpoint) {
        if (!endpoint.isURI() || !endpoint.getURI().startsWith("values:")) {
            return null;
        }
        String suffix = endpoint.getURI().substring("values:".length());
        if (suffix.isEmpty()) {
            return 0;
        }
        if (!suffix.matches("[0-9]+")) {
            throw new QueryExecException("Invalid values SERVICE counter: " + endpoint
                    + "; expected values: or values:N with a nonnegative integer N");
        }
        try {
            return Integer.valueOf(suffix);
        } catch (NumberFormatException e) {
            throw new QueryExecException("Values SERVICE counter exceeds integer range: " + endpoint, e);
        }
    }

    private static void validate(Op op, int depth, Binding binding) {
        services(op, service -> {
            Integer count = counter(Substitute.substitute(service.getService(), binding));
            if (count != null) {
                if (count > depth) {
                    throw new QueryExecException("Values SERVICE counter " + count
                            + " exceeds available SERVICE depth " + depth + ": " + service.getService());
                }
                // A marker's workload is a new local execution, not another forwarding boundary.
                validate(service.getSubOp(), 0, binding);
            } else {
                validate(service.getSubOp(), depth + 1, binding);
            }
            return service;
        });
    }

    private static Op rewrite(Op op, Binding binding, ExecutionContext execCxt) {
        return services(op, service -> {
            Integer count = counter(Substitute.substitute(service.getService(), binding));
            if (count != null) {
                if (count == 0) {
                    return materialize(service, binding, execCxt);
                }
                // Do not enter a deferred workload or decrement again for deeper syntax nesting.
                return new OpService(NodeFactory.createURI("values:" + (count - 1)),
                        service.getSubOp(), service.getSilent());
            }
            Op body = rewrite(service.getSubOp(), binding, execCxt);
            return body == service.getSubOp() ? service
                    : new OpService(service.getService(), body, service.getSilent());
        });
    }

    /** Apply an action to outermost SERVICE nodes, including in EXISTS expressions. */
    private static Op services(Op op, Function<OpService, Op> action) {
        // Jena's transformSkipService leaves the SERVICE node itself untouched.
        // Walk normally but only act on outermost services; nested workloads are
        // processed explicitly by the action, preserving deferred scope boundaries.
        int[] depth = {0};
        return Transformer.transform(new TransformCopy() {
            @Override
            public Op transform(OpService service, Op ignored) {
                return depth[0] == 1 ? action.apply(service) : service;
            }
        }, new ExprTransformCopy() {
            @Override
            public Expr transform(ExprFunctionOp function, ExprList args, Op graphPattern) {
                if (graphPattern == function.getGraphPattern()) {
                    return function;
                }
                // Jena 6.2 collapses a single-element EXISTS group during serialization.
                // An empty BGP is a join identity and keeps the required braces intact,
                // including after binding substitution and reverse scope renaming.
                OpSequence group = OpSequence.create();
                group.add(graphPattern);
                group.add(new OpBGP());
                return function.copy(args, group);
            }
        }, op, new OpVisitorBase() {
            @Override public void visit(OpService service) { depth[0]++; }
        }, new OpVisitorBase() {
            @Override public void visit(OpService service) { depth[0]--; }
        });
    }

    private static boolean isCancelled(ExecutionContext execCxt) {
        return execCxt.getCancelSignal() != null && execCxt.getCancelSignal().get();
    }

    private static OpTable materialize(OpService service, Binding binding, ExecutionContext execCxt) {
        List<Var> vars = new ArrayList<>(OpVars.visibleVars(service.getSubOp()));
        List<Binding> rows = new ArrayList<>();
        QueryIterator iterator = null;
        try {
            if (isCancelled(execCxt)) {
                throw new QueryCancelledException();
            }
            iterator = QC.execute(service.getSubOp(), binding, execCxt);
            while (iterator.hasNext()) {
                if (isCancelled(execCxt)) {
                    throw new QueryCancelledException();
                }
                Binding result = iterator.nextBinding();
                BindingBuilder row = BindingBuilder.create();
                for (Var var : vars) {
                    Node value = result.get(var);
                    if (value != null) {
                        if (!value.isURI() && !value.isLiteral()) {
                            throw new QueryExecException("Cannot serialize VALUES term for " + var + ": " + value);
                        }
                        row.add(var, value);
                    }
                }
                rows.add(row.build());
            }
        } catch (RuntimeException e) {
            if (!service.getSilent() || e instanceof QueryCancelledException || isCancelled(execCxt)) {
                throw e;
            }
            return OpTable.unit();
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }
        return OpTable.create(new TableData(vars, rows));
    }
}

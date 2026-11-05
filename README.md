# Jena Values Service

Inject dynamically computed binding tables into remote SPARQL requests. A Jena
`ChainingServiceExecutor` evaluates `SERVICE <values:> { P }` against the current
execution context's dataset and active graph, then replaces that SERVICE with an
`OpTable`—serialized as `VALUES` in the outgoing request.

## Complete example

Suppose the local dataset's default graph contains these three triples:

```turtle
<urn:alice> a <urn:Selected> .
<urn:bob>   a <urn:Selected> .
<urn:carol> a <urn:Other> .
```

Run this query locally to select Alice and Bob and fetch their labels from a remote endpoint:

```sparql
SELECT ?x ?label {
  SERVICE <https://example.org/sparql> {
    SERVICE <values:> { # Executed locally, outside the enclosing SERVICE clause.
      SELECT DISTINCT ?x WHERE { ?x a <urn:Selected> }
    }
    ?x <urn:label> ?label
  }
}
```

The plugin evaluates the inner `SELECT` against the local dataset and replaces
its `SERVICE <values:>` block with this binding table. Carol is excluded because
she has type `<urn:Other>`:

```sparql
VALUES ?x {
  <urn:alice>
  <urn:bob>
}
```

The query sent to `https://example.org/sparql` is equivalent to:

```sparql
SELECT * WHERE {
  VALUES ?x {
    <urn:alice>
    <urn:bob>
  }
  ?x <urn:label> ?label
}
```

The remote endpoint supplies the labels; the three local triples only select
which resources to look up. The remote endpoint receives standard SPARQL and
does not need this plugin once all values markers have been materialized.

Tables support multiple columns, duplicates, unbound cells (`UNDEF`), and empty
results. Use `SELECT DISTINCT` inside the marker when a unique list is desired.
Current input bindings are respected.

## Choosing the execution location

`values:` and `values:0` materialize locally. `values:N` defers materialization
across N enclosing ordinary SERVICE boundaries. Each forwarding step decrements
the counter once; a marker that becomes zero is evaluated at the next location.
For example:

```sparql
SERVICE <https://a.example/sparql> {
  SERVICE <https://b.example/sparql> {
    SERVICE <values:1> { ?x a <urn:Selected> }
    ?x <urn:label> ?label
  }
}
```

This obtains the table from A's dataset. Use `values:0` for the initiating dataset
or `values:2` for B's dataset. **Any endpoint receiving unresolved markers needs the
plugin.** Deferred marker bodies remain untouched until their destination handles
them. Write decimal counters without brackets. Malformed counters, integer
overflow, and counters exceeding the enclosing SERVICE depth raise errors.
Marker workloads start a fresh scope for depth validation.

Materialization preserves Jena's internal variable scopes; ordinary executors
handle transport and result-name mapping. `SERVICE SILENT <values:>` returns an
identity table on workload failure. Local validation errors and cancellation
propagate; remote errors follow the enclosing SERVICE's SILENT behavior. Blank nodes and terms other than IRIs/literals are rejected. Results
are buffered in memory; there is no caching or automatic batching.

Union-default-graph mode honors the current and legacy TDB request-context flags
(`http://jena.apache.org/TDB#unionDefaultGraph` and
`http://jena.hpl.hp.com/TDB#unionDefaultGraph`). In default-graph scope, local
materialization selects the dataset's union graph in a copied execution context.
Explicit named active graphs and `GRAPH` clauses inside the workload retain their
scope. jena-proxy publishes the current TDB symbol for compatibility. The library
has no runtime dependency on TDB; TDB2 is used in regression tests.
TDB2's quad planning can remove an enclosing `GRAPH { SERVICE ... }` wrapper
before interception; put `GRAPH` inside the values workload when specifying its
local source graph.

## Build and use

Requires Java 21+, Maven, and Jena 6.2.0.

```sh
mvn clean verify
mvn -Pbundle clean package   # or: make fuseki-plugin
```

For ARQ, add `org.aksw.jena.valuesservice:jena-valuesservice:0.1.0-SNAPSHOT` to the
classpath. Jena's subsystem lifecycle registers the executor automatically.
For a custom registry, call `JenaPluginValuesService.init(registry)`.

For Fuseki, add
`jena-valuesservice-pkg-fuseki-plugin/target/jena-valuesservice-fuseki-plugin-0.1.0-SNAPSHOT.jar`
to the server's startup classpath and restart. The bundle includes the plugin and
lifecycle descriptor; Fuseki supplies Jena. No separate Fuseki module is needed.

Licensed under the [Apache License, Version 2.0](LICENSE).

## Changelog

Newest versions first.

| Version | Highlights |
| --- | --- |
| [v0.6.1](https://github.com/jenax-plus/jena-valuesservice/releases/tag/v0.6.1) | Adds union-default-graph support using the TDB request-context flags, with copied materialization contexts and TDB2 regression tests. Fixes the Makefile and Fuseki plugin packaging. |
| [v0.6.0](https://github.com/jenax-plus/jena-valuesservice/releases/tag/v0.6.0) | Initial release: dynamic SERVICE-to-VALUES substitution, deferred materialization through hop counters, and automatic ARQ/Fuseki registration. |

# Fuseki plugin bundle

From the repository root, run `mvn -Pbundle clean package` or `make fuseki-plugin`.
The resulting `target/jena-valuesservice-fuseki-plugin-0.1.0-SNAPSHOT.jar` contains
the Values Service implementation and Jena subsystem lifecycle descriptor.
Jena dependencies are provided by the Fuseki installation.

Put the JAR on Fuseki's startup classpath and restart. See the root README for
query syntax and execution semantics.

MVN ?= mvn
ARGS ?=

.PHONY: help test fuseki-plugin

help: ## Show available targets
	@echo 'make test           Run the test suite'
	@echo 'make fuseki-plugin  Test and package the Fuseki plugin JAR'

test: ## Run the test suite
	$(MVN) test $(ARGS)

fuseki-plugin: ## Test and package the Fuseki plugin JAR
	$(MVN) -Pbundle clean package $(ARGS)

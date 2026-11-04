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

import org.apache.jena.sparql.service.ServiceExecutorRegistry;
import org.apache.jena.sys.JenaSubsystemLifecycle;

/** Automatically registers the executor on the ARQ classpath, including in Fuseki. */
public class JenaPluginValuesService implements JenaSubsystemLifecycle {
    /** Register once in the supplied registry (also suitable for query-local registries). */
    public static void init(ServiceExecutorRegistry registry) {
        if (!registry.getSingleChain().contains(ValuesServiceExecutor.INSTANCE)) {
            registry.addSingleLink(ValuesServiceExecutor.INSTANCE);
        }
    }

    @Override
    public void start() {
        init(ServiceExecutorRegistry.get());
    }

    @Override
    public void stop() {
        ServiceExecutorRegistry.get().removeSingleLink(ValuesServiceExecutor.INSTANCE);
    }

    @Override
    public int level() {
        return 1000;
    }
}

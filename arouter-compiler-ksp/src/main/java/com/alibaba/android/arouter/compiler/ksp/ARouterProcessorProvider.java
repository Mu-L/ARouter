package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;

/** The KSP entry point. Keep this artifact off the application's runtime classpath. */
public final class ARouterProcessorProvider implements SymbolProcessorProvider {
    @Override
    public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return new ARouterSymbolProcessor(environment);
    }
}

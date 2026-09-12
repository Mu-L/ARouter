package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.symbol.KSAnnotated;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/** Coordinates error handling and round completion across ARouter generators. */
final class ARouterSymbolProcessor implements SymbolProcessor {
    private final List<ManagedSymbolProcessor> processors;
    private boolean failed;

    ARouterSymbolProcessor(SymbolProcessorEnvironment environment) {
        processors = Arrays.asList(
                new RouteSymbolProcessor(environment),
                new AutowiredSymbolProcessor(environment),
                new InterceptorSymbolProcessor(environment));
    }

    @Override
    public List<KSAnnotated> process(Resolver resolver) {
        if (hasErrors()) {
            onError();
            return Collections.emptyList();
        }
        Set<KSAnnotated> deferred = new LinkedHashSet<>();
        for (ManagedSymbolProcessor processor : processors) {
            deferred.addAll(processor.process(resolver));
            if (processor.hasErrors()) {
                onError();
                return Collections.emptyList();
            }
        }
        return new ArrayList<>(deferred);
    }

    @Override
    public void finish() {
        if (hasErrors()) {
            onError();
            return;
        }
        for (ManagedSymbolProcessor processor : processors) {
            processor.prepareFinish();
        }
        if (hasErrors()) {
            onError();
            return;
        }
        for (ManagedSymbolProcessor processor : processors) {
            processor.emitFinish();
            if (processor.hasErrors()) {
                onError();
                return;
            }
        }
    }

    private boolean hasErrors() {
        if (failed) {
            return true;
        }
        for (ManagedSymbolProcessor processor : processors) {
            if (processor.hasErrors()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onError() {
        failed = true;
        for (ManagedSymbolProcessor processor : processors) {
            processor.onError();
        }
    }
}

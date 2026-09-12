package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.SymbolProcessor;

/** Validates every generator before any aggregate registry is emitted. */
interface ManagedSymbolProcessor extends SymbolProcessor {
    boolean hasErrors();

    void prepareFinish();

    void emitFinish();

    @Override
    default void finish() {
        prepareFinish();
        if (!hasErrors()) {
            emitFinish();
        }
    }
}

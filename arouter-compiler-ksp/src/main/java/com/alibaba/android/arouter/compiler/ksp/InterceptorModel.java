package com.alibaba.android.arouter.compiler.ksp;

import com.squareup.javapoet.ClassName;

/** Symbol-free interceptor metadata that is safe to retain across KSP rounds. */
final class InterceptorModel {
    final ClassName implementation;
    final String qualifiedName;
    final int priority;

    InterceptorModel(ClassName implementation, String qualifiedName, int priority) {
        this.implementation = implementation;
        this.qualifiedName = qualifiedName;
        this.priority = priority;
    }
}

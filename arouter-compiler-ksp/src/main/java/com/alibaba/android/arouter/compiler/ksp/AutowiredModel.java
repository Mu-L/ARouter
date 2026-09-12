package com.alibaba.android.arouter.compiler.ksp;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable injection plan; no KSP symbol escapes its resolver round. */
final class AutowiredModel {
    final ClassName target;
    final String helperName;
    final String bundleSource;
    final List<Field> fields;

    AutowiredModel(ClassName target, String helperName, String bundleSource, List<Field> fields) {
        this.target = target;
        this.helperName = helperName;
        this.bundleSource = bundleSource;
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
    }

    static final class Field {
        final String name;
        final String key;
        final TypeName type;
        final ClassName providerType;
        final boolean providerByName;
        final int kind;
        final boolean required;

        Field(String name, String key, TypeName type, ClassName providerType,
                boolean providerByName, int kind, boolean required) {
            this.name = name;
            this.key = key;
            this.type = type;
            this.providerType = providerType;
            this.providerByName = providerByName;
            this.kind = kind;
            this.required = required;
        }
    }
}

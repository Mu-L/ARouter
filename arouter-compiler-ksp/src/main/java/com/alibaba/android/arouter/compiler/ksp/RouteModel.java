package com.alibaba.android.arouter.compiler.ksp;

import com.squareup.javapoet.ClassName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A snapshot of the information needed by the emitter. KSP symbols are deliberately
 * absent: they belong to one resolver round and must not enter the runtime model.
 */
final class RouteModel {
    final ClassName destination;
    final String qualifiedName;
    final String path;
    final String group;
    final String type;
    final String description;
    final int priority;
    final int extras;
    final Map<String, Parameter> parameters;
    final List<String> providerKeys;
    final List<String> providerPrototypes;

    RouteModel(ClassName destination, String qualifiedName, String path, String group,
            String type, String description, int priority, int extras,
            Map<String, Parameter> parameters, List<String> providerKeys,
            List<String> providerPrototypes) {
        this.destination = destination;
        this.qualifiedName = qualifiedName;
        this.path = path;
        this.group = group;
        this.type = type;
        this.description = description;
        this.priority = priority;
        this.extras = extras;
        this.parameters = Collections.unmodifiableMap(new TreeMap<>(parameters));
        this.providerKeys = Collections.unmodifiableList(new ArrayList<>(providerKeys));
        this.providerPrototypes = Collections.unmodifiableList(new ArrayList<>(providerPrototypes));
    }

    static final class Parameter {
        final int kind;
        final String description;
        final boolean required;

        Parameter(int kind, String description, boolean required) {
            this.kind = kind;
            this.description = description;
            this.required = required;
        }
    }
}

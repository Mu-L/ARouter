package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.gson.GsonBuilder;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.lang.model.element.Modifier;

/** Emits the same runtime entry points as the javac processor. */
final class RouteEmitter {
    static final String PACKAGE = "com.alibaba.android.arouter.routes";
    private static final ClassName META =
            ClassName.get("com.alibaba.android.arouter.facade.model", "RouteMeta");
    private static final ClassName TYPE =
            ClassName.get("com.alibaba.android.arouter.facade.enums", "RouteType");
    private static final ClassName GROUP =
            ClassName.get("com.alibaba.android.arouter.facade.template", "IRouteGroup");
    private static final String[] PARAM_TYPES = {
        "boolean", "byte", "short", "int", "long", "char", "float", "double",
        "string", "serializable", "parcelable", "object"
    };
    private final CodeGenerator generator;

    RouteEmitter(CodeGenerator generator) {
        this.generator = generator;
    }

    void emit(String module, boolean generateDocs, List<RouteModel> routes,
            Dependencies dependencies) throws IOException {
        Map<String, List<RouteModel>> groups = new TreeMap<>();
        for (RouteModel route : routes) {
            groups.computeIfAbsent(route.group, ignored -> new ArrayList<>()).add(route);
        }
        MethodSpec.Builder root = method("routes", ParameterizedTypeName.get(
                ClassName.get(Map.class), ClassName.get(String.class),
                ParameterizedTypeName.get(ClassName.get(Class.class),
                        WildcardTypeName.subtypeOf(GROUP))));
        MethodSpec.Builder providers = method("providers", routeMapType());
        Map<String, List<Map<String, Object>>> documents = new TreeMap<>();
        for (Map.Entry<String, List<RouteModel>> entry : groups.entrySet()) {
            String name = "ARouter$$Group$$" + entry.getKey();
            MethodSpec.Builder group = method("atlas", routeMapType());
            List<Map<String, Object>> groupDocs = new ArrayList<>();
            for (RouteModel route : entry.getValue()) {
                group.addStatement("atlas.put($S, $L)", route.path, metadata(route, true));
                for (String key : route.providerKeys) {
                    providers.addStatement("providers.put($S, $L)", key, metadata(route, false));
                }
                if (generateDocs) {
                    groupDocs.add(document(route));
                }
            }
            writeClass(name, GROUP, group.build(), dependencies);
            root.addStatement("routes.put($S, $T.class)", entry.getKey(), ClassName.get(PACKAGE, name));
            documents.put(entry.getKey(), groupDocs);
        }
        // Empty maps are intentional: deleting the last route must not retain stale registrations.
        writeClass("ARouter$$Providers$$" + module,
                ClassName.get("com.alibaba.android.arouter.facade.template", "IProviderGroup"),
                providers.build(), dependencies);
        writeClass("ARouter$$Root$$" + module,
                ClassName.get("com.alibaba.android.arouter.facade.template", "IRouteRoot"),
                root.build(), dependencies);
        if (generateDocs) {
            try (Writer writer = new OutputStreamWriter(generator.createNewFile(dependencies,
                    "com.alibaba.android.arouter.docs", "arouter-map-of-" + module, "json"),
                    StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(documents, writer);
            }
        }
    }

    private static ParameterizedTypeName routeMapType() {
        return ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), META);
    }

    private static MethodSpec.Builder method(String parameter, ParameterizedTypeName type) {
        return MethodSpec.methodBuilder("loadInto")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(type, parameter);
    }

    private static CodeBlock metadata(RouteModel route, boolean groupEntry) {
        CodeBlock params = CodeBlock.of("null");
        if (groupEntry && !route.parameters.isEmpty()) {
            CodeBlock.Builder entries = CodeBlock.builder()
                    .add("new $T<String, Integer>() {{\n", java.util.HashMap.class).indent();
            for (Map.Entry<String, RouteModel.Parameter> parameter : route.parameters.entrySet()) {
                // This expression is embedded inside loadInto's outer addStatement.
                // Nested JavaPoet statement markers are invalid; retain literal escaping
                // while writing these initializer statements without additional markers.
                entries.add("put($S, $L);\n", parameter.getKey(), parameter.getValue().kind);
            }
            params = entries.unindent().add("}}").build();
        }
        // Preserve the existing group-table versus provider-index normalization contract.
        return CodeBlock.of("$T.build($T.$L, $T.class, $S, $S, $L, $L, $L)",
                META, TYPE, route.type, route.destination,
                groupEntry ? route.path.toLowerCase(Locale.ROOT) : route.path,
                groupEntry ? route.group.toLowerCase(Locale.ROOT) : route.group,
                params, route.priority, route.extras);
    }

    private void writeClass(String name, ClassName contract, MethodSpec method,
            Dependencies dependencies) throws IOException {
        JavaFile file = JavaFile.builder(PACKAGE, TypeSpec.classBuilder(name)
                .addJavadoc("Generated by ARouter KSP. Do not edit.\n")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(contract)
                .addMethod(method)
                .build()).build();
        try (OutputStream output = generator.createNewFile(dependencies, PACKAGE, name, "java");
                Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            file.writeTo(writer);
        }
    }

    private static Map<String, Object> document(RouteModel route) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("group", route.group);
        document.put("path", route.path);
        if (!route.description.isEmpty()) {
            document.put("description", route.description);
        }
        if (!route.providerPrototypes.isEmpty()) {
            document.put("prototype", String.join(", ", route.providerPrototypes));
        }
        document.put("className", route.destination.canonicalName());
        document.put("type", route.type.toLowerCase(Locale.ROOT));
        document.put("mark", route.extras);
        if (!route.parameters.isEmpty()) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (Map.Entry<String, RouteModel.Parameter> entry : route.parameters.entrySet()) {
                RouteModel.Parameter parameter = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", entry.getKey());
                item.put("type", PARAM_TYPES[parameter.kind]);
                if (!parameter.description.isEmpty()) {
                    item.put("description", parameter.description);
                }
                item.put("required", parameter.required);
                params.add(item);
            }
            document.put("params", params);
        }
        return document;
    }
}

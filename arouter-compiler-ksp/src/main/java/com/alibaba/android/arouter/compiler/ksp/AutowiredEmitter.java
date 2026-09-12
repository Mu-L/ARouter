package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import javax.lang.model.element.Modifier;

/** Emits direct JVM field writes so uninitialized Kotlin lateinit getters are never read. */
final class AutowiredEmitter {
    private static final ClassName ROUTER = ClassName.get("com.alibaba.android.arouter.launcher", "ARouter");
    private static final ClassName LOG = ClassName.get("android.util", "Log");
    private static final ClassName BUNDLE = ClassName.get("android.os", "Bundle");
    private static final ClassName SERIALIZER =
            ClassName.get("com.alibaba.android.arouter.facade.service", "SerializationService");
    private static final ClassName TYPE_WRAPPER =
            ClassName.get("com.alibaba.android.arouter.facade.model", "TypeWrapper");
    private static final String[] GETTERS = {
        "getBoolean", "getByte", "getShort", "getInt", "getLong", "getChar", "getFloat", "getDouble"
    };
    private final CodeGenerator generator;

    AutowiredEmitter(CodeGenerator generator) {
        this.generator = generator;
    }

    void emit(AutowiredModel model, Dependencies dependencies) throws IOException {
        MethodSpec.Builder inject = MethodSpec.methodBuilder("inject")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Object.class, "target")
                .addStatement("$T substitute = ($T) target", model.target, model.target);
        boolean needsSerializer = model.fields.stream().anyMatch(field -> field.providerType == null && field.kind == 11);
        if (needsSerializer) {
            inject.addStatement("$T serializationService = $T.getInstance().navigation($T.class)",
                    SERIALIZER, ROUTER, SERIALIZER);
        }
        if ("ACTIVITY".equals(model.bundleSource)) {
            inject.addStatement("$T bundle = substitute.getIntent() == null ? null : substitute.getIntent().getExtras()",
                    BUNDLE);
        } else if ("FRAGMENT".equals(model.bundleSource)) {
            inject.addStatement("$T bundle = substitute.getArguments()", BUNDLE);
        }
        int fieldIndex = 0;
        for (AutowiredModel.Field field : model.fields) {
            if (field.providerType != null) {
                if (field.providerByName) {
                    inject.addStatement("substitute.$L = ($T) $T.getInstance().build($S).navigation()",
                            field.name, field.type, ROUTER, field.key);
                } else {
                    inject.addStatement("substitute.$L = ($T) $T.getInstance().navigation($T.class)",
                            field.name, field.type, ROUTER, field.providerType);
                }
                if (field.required) {
                    inject.beginControlFlow("if (substitute.$L == null)", field.name)
                            .addStatement("throw new $T($S + $T.class.getName() + $S)", RuntimeException.class,
                                    "The field '" + field.name + "' is null, in class '", model.target, "!")
                            .endControlFlow();
                }
                continue;
            }
            inject.beginControlFlow("if (bundle != null && bundle.containsKey($S))", field.key);
            if (field.kind == 11) {
                String value = "arouterValue" + fieldIndex++;
                inject.beginControlFlow("if (serializationService != null)")
                        .addStatement("$T $L = serializationService.parseObject(bundle.getString($S), new $T<$T>() {}.getType())",
                                field.type, value, field.key, TYPE_WRAPPER, field.type)
                        .beginControlFlow("if ($L != null)", value)
                        .addStatement("substitute.$L = $L", field.name, value)
                        .endControlFlow()
                        .nextControlFlow("else")
                        .addStatement("$T.e($S, $S)", LOG, "ARouter::",
                                "You want automatic inject the field '" + field.name + "' in class '"
                                        + model.target.canonicalName()
                                        + "', then you should implement 'SerializationService' to support object auto inject!")
                        .endControlFlow();
            } else if (field.type.isPrimitive()) {
                inject.addStatement("substitute.$L = bundle.$L($S, substitute.$L)",
                        field.name, GETTERS[field.kind], field.key, field.name);
            } else {
                inject.addStatement("substitute.$L = ($T) bundle.get($S)", field.name, field.type, field.key);
            }
            inject.endControlFlow();
            if (field.required && !field.type.isPrimitive()) {
                inject.beginControlFlow("if (substitute.$L == null)", field.name)
                        .addStatement("$T.e($S, $S + $T.class.getName() + $S)", LOG, "ARouter::",
                                "The field '" + field.name + "' is null, in class '", model.target, "!")
                        .endControlFlow();
            }
        }
        JavaFile javaFile = JavaFile.builder(model.target.packageName(),
                TypeSpec.classBuilder(model.helperName)
                        .addJavadoc("Generated by ARouter KSP. Do not edit.\n")
                        .addModifiers(Modifier.PUBLIC)
                        .addSuperinterface(ClassName.get("com.alibaba.android.arouter.facade.template", "ISyringe"))
                        .addMethod(inject.build())
                        .build()).build();
        try (Writer writer = new OutputStreamWriter(generator.createNewFile(dependencies,
                model.target.packageName(), model.helperName, "java"), StandardCharsets.UTF_8)) {
            javaFile.writeTo(writer);
        }
    }
}

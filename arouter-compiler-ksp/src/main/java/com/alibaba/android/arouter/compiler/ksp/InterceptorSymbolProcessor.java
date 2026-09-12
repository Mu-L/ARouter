package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.KSPLogger;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSNode;
import com.google.devtools.ksp.symbol.KSTypeReference;
import com.google.devtools.ksp.symbol.KSValueParameter;
import com.google.devtools.ksp.symbol.Modifier;
import com.google.devtools.ksp.symbol.Origin;
import com.squareup.javapoet.ClassName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Collects interceptor declarations independently from route processing. */
final class InterceptorSymbolProcessor implements ManagedSymbolProcessor {
    private static final String ANNOTATION = "com.alibaba.android.arouter.facade.annotation.Interceptor";
    private static final String CONTRACT = "com.alibaba.android.arouter.facade.template.IInterceptor";
    private final KSPLogger logger;
    private final InterceptorEmitter emitter;
    private final String module;
    private final Map<String, InterceptorModel> models = new TreeMap<>();
    private final Map<String, String> unresolved = new TreeMap<>();
    private final ProcessingRound round;
    private final java.util.Set<String> originPaths = new java.util.TreeSet<>();
    private List<InterceptorModel> ordered = Collections.emptyList();
    private boolean failed;
    private boolean prepared;

    InterceptorSymbolProcessor(SymbolProcessorEnvironment environment, ProcessingRound round) {
        this.round = round;
        logger = environment.getLogger();
        emitter = new InterceptorEmitter(environment.getCodeGenerator());
        String configured = environment.getOptions().get("AROUTER_MODULE_NAME");
        module = configured == null ? "" : configured.replaceAll("[^0-9a-zA-Z_]+", "");
        // The coordinator's route processor owns the user-facing module option diagnostic.
        failed = module.isEmpty();
    }

    @Override
    public List<KSAnnotated> process(Resolver resolver) {
        List<KSAnnotated> deferred = new ArrayList<>();
        Iterator<KSAnnotated> symbols = resolver.getSymbolsWithAnnotation(ANNOTATION, false).iterator();
        while (symbols.hasNext()) {
            KSAnnotated symbol = symbols.next();
            if (!(symbol instanceof KSClassDeclaration)) {
                error("@Interceptor must annotate a class.", symbol);
                continue;
            }
            KSClassDeclaration declaration = (KSClassDeclaration) symbol;
            if (declaration.getContainingFile() == null) { continue; }
            String name = KspSymbols.qualifiedName(declaration);
            if (name == null) {
                error("@Interceptor cannot annotate a local or anonymous class.", declaration);
                continue;
            }
            if (models.containsKey(name)) { continue; }
            try {
                InterceptorModel model = parse(declaration);
                unresolved.remove(name);
                if (model != null) {
                    models.put(name, model);
                    originPaths.add(declaration.getContainingFile().getFilePath());
                }
            } catch (KspSymbols.UnresolvedType exception) {
                unresolved.put(name, exception.getMessage());
                deferred.add(declaration);
            } catch (KspSymbols.InvalidSymbol exception) {
                unresolved.remove(name);
                error("Invalid @Interceptor [" + name + "]: " + exception.getMessage(), declaration);
            }
        }
        return deferred;
    }

    private InterceptorModel parse(KSClassDeclaration declaration) {
        if (declaration.getClassKind() != ClassKind.CLASS || UtilsKt.isAbstract(declaration)
                || declaration.getModifiers().contains(Modifier.INNER)) {
            error("@Interceptor requires a concrete class with a public no-argument constructor; "
                    + "interfaces, objects, abstract and inner classes are not supported.", declaration);
            return null;
        }
        KspSymbols.requireAccessible(declaration, RouteEmitter.PACKAGE);
        ClassName implementation = KspSymbols.className(declaration);
        KSDeclaration enclosing = declaration.getParentDeclaration();
        if (declaration.getOrigin() == Origin.JAVA && enclosing instanceof KSClassDeclaration
                && ((KSClassDeclaration) enclosing).getClassKind() != ClassKind.INTERFACE
                && ((KSClassDeclaration) enclosing).getClassKind() != ClassKind.ANNOTATION_CLASS
                && !declaration.getModifiers().contains(Modifier.JAVA_STATIC)) {
            error("Nested Java @Interceptor classes must be static for runtime construction.", declaration);
            return null;
        }
        boolean direct = false;
        Iterator<KSTypeReference> supers = declaration.getSuperTypes().iterator();
        while (supers.hasNext()) {
            String superName = KspSymbols.qualifiedName(KspSymbols.expand(supers.next().resolve()).getDeclaration());
            if (CONTRACT.equals(superName)) { direct = true; }
        }
        if (!direct) {
            error("@Interceptor [" + KspSymbols.qualifiedName(declaration)
                    + "] must directly implement IInterceptor, matching the APT compiler contract.", declaration);
            return null;
        }
        if (!hasPublicNoArgumentConstructor(declaration)) {
            error("@Interceptor [" + KspSymbols.qualifiedName(declaration)
                    + "] must provide a public no-argument constructor for runtime registration.", declaration);
            return null;
        }
        Map<String, Object> annotation = KspSymbols.annotation(declaration, ANNOTATION);
        if (annotation == null || !(annotation.get("priority") instanceof Number)) {
            throw new KspSymbols.UnresolvedType("the @Interceptor priority");
        }
        return new InterceptorModel(implementation, KspSymbols.qualifiedName(declaration),
                ((Number) annotation.get("priority")).intValue());
    }

    private boolean hasPublicNoArgumentConstructor(KSClassDeclaration declaration) {
        Iterator<KSFunctionDeclaration> constructors = UtilsKt.getConstructors(declaration).iterator();
        boolean any = false;
        while (constructors.hasNext()) {
            any = true;
            KSFunctionDeclaration constructor = constructors.next();
            if (!UtilsKt.isPublic(constructor) && !UtilsKt.isInternal(constructor)) { continue; }
            if (constructor.getParameters().isEmpty()) { return true; }
            boolean allDefault = true;
            for (KSValueParameter parameter : constructor.getParameters()) {
                if (!parameter.getHasDefault()) { allDefault = false; break; }
            }
            // Kotlin supplies a JVM no-arg constructor for an all-default primary
            // constructor. Secondary constructors need @JvmOverloads to expose it.
            if (allDefault && declaration.getOrigin() == Origin.KOTLIN
                    && (Objects.equals(constructor, declaration.getPrimaryConstructor())
                    || KspSymbols.annotation(constructor, "kotlin.jvm.JvmOverloads") != null)) {
                return true;
            }
        }
        return !any; // The implicit public constructor of a public class.
    }

    @Override
    public void prepareFinish() {
        if (prepared) { return; }
        prepared = true;
        for (Map.Entry<String, String> entry : unresolved.entrySet()) {
            error("Cannot resolve types needed by @Interceptor [" + entry.getKey() + "]: "
                    + entry.getValue() + ". Generate these types with KSP or provide a compiled dependency.", null);
        }
        Map<Integer, InterceptorModel> byPriority = new TreeMap<>();
        for (InterceptorModel model : models.values()) {
            InterceptorModel previous = byPriority.putIfAbsent(model.priority, model);
            if (previous != null) {
                error("Duplicate interceptor priority [" + model.priority + "] found on ["
                        + previous.qualifiedName + "] and [" + model.qualifiedName + "].", null);
            }
        }
        ordered = new ArrayList<>(byPriority.values());
    }

    @Override
    public void emitFinish() {
        if (!prepared || failed) { return; }
        try {
            emitter.emit(module, ordered, new Dependencies(true, round.origins(originPaths)));
        } catch (IOException exception) {
            error("Could not generate interceptor registry: " + exception.getMessage()
                    + ". Use only one ARouter processor backend per module.", null);
        }
    }

    @Override public boolean hasErrors() { return failed; }
    @Override public void finish() { prepareFinish(); emitFinish(); }
    @Override public void onError() { failed = true; }

    private void error(String message, KSNode node) {
        failed = true;
        logger.error("ARouter: " + message, node);
    }
}

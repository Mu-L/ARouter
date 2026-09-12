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
import com.google.devtools.ksp.symbol.KSNode;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.Modifier;
import com.google.devtools.ksp.symbol.Origin;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.SourceVersion;

/** Generates one isolating helper per declaring class, never a partial helper. */
final class AutowiredSymbolProcessor implements ManagedSymbolProcessor {
    private static final String ANNOTATION = "com.alibaba.android.arouter.facade.annotation.Autowired";
    private static final String PROVIDER = "com.alibaba.android.arouter.facade.template.IProvider";
    private final KSPLogger logger;
    private final AutowiredEmitter emitter;
    private final Set<String> generated = new HashSet<>();
    private final Map<String, String> unresolved = new TreeMap<>();
    private boolean failed;

    AutowiredSymbolProcessor(SymbolProcessorEnvironment environment) {
        logger = environment.getLogger();
        emitter = new AutowiredEmitter(environment.getCodeGenerator());
    }

    @Override
    public List<KSAnnotated> process(Resolver resolver) {
        Map<String, KSClassDeclaration> targets = new TreeMap<>();
        Map<String, List<KSAnnotated>> annotatedFields = new TreeMap<>();
        Iterator<KSAnnotated> symbols = resolver.getSymbolsWithAnnotation(ANNOTATION, false).iterator();
        while (symbols.hasNext()) {
            KSAnnotated symbol = symbols.next();
            if (UtilsKt.getContainingFile(symbol) == null) {
                continue;
            }
            if (!(symbol instanceof KSPropertyDeclaration)) {
                error("@Autowired must annotate a supported instance field.", symbol);
                continue;
            }
            KSDeclaration parent = ((KSPropertyDeclaration) symbol).getParentDeclaration();
            if (!(parent instanceof KSClassDeclaration) || parent.getQualifiedName() == null) {
                error("@Autowired requires a field in a named class; top-level/local fields are unsupported.", symbol);
                continue;
            }
            String name = KspSymbols.qualifiedName(parent);
            targets.put(name, (KSClassDeclaration) parent);
            annotatedFields.computeIfAbsent(name, ignored -> new ArrayList<>()).add(symbol);
        }
        List<KSAnnotated> deferred = new ArrayList<>();
        for (Map.Entry<String, KSClassDeclaration> entry : targets.entrySet()) {
            if (generated.contains(entry.getKey())) {
                continue;
            }
            try {
                KSClassDeclaration target = entry.getValue();
                AutowiredModel model = parse(target, resolver);
                // Type traversal records referenced source dependencies automatically;
                // only the declaring source is an originating root for this helper.
                emitter.emit(model, new Dependencies(false, target.getContainingFile()));
                generated.add(entry.getKey());
                unresolved.remove(entry.getKey());
            } catch (KspSymbols.UnresolvedType exception) {
                unresolved.put(entry.getKey(), exception.getMessage());
                deferred.addAll(annotatedFields.get(entry.getKey()));
            } catch (KspSymbols.InvalidSymbol exception) {
                unresolved.remove(entry.getKey());
                error(exception.getMessage(), entry.getValue());
            } catch (IOException exception) {
                error("Could not generate @Autowired helper for [" + entry.getKey() + "]: "
                        + exception.getMessage() + ". Use only one ARouter processor backend per module.",
                        entry.getValue());
            }
        }
        return deferred;
    }

    private AutowiredModel parse(KSClassDeclaration target, Resolver resolver) {
        if (target.getClassKind() != ClassKind.CLASS) {
            throw new KspSymbols.InvalidSymbol("@Autowired requires instance fields in a class, not an object, "
                    + "companion object, enum or interface.");
        }
        ClassName className = KspSymbols.className(target);
        KspSymbols.requireAccessible(target, className.packageName());
        List<AutowiredModel.Field> fields = new ArrayList<>();
        Iterator<KSDeclaration> members = target.getDeclarations().iterator();
        boolean needsBundle = false;
        while (members.hasNext()) {
            KSDeclaration member = members.next();
            if (!(member instanceof KSPropertyDeclaration)) {
                continue;
            }
            Map<String, Object> config = KspSymbols.annotation(member, ANNOTATION);
            if (config == null) {
                continue;
            }
            KSPropertyDeclaration field = (KSPropertyDeclaration) member;
            try {
                validateField(field);
                KSType canonical = KspTypeNames.canonicalType(field, resolver);
                TypeName type = KspTypeNames.fieldType(field, canonical, resolver);
                boolean provider = KspSymbols.hierarchy(canonical).contains(PROVIDER);
                String configuredName = KspSymbols.string(config, "name");
                fields.add(new AutowiredModel.Field(field.getSimpleName().asString(),
                        configuredName.isEmpty() ? field.getSimpleName().asString() : configuredName,
                        type, provider ? KspTypeNames.rawClass(canonical, resolver) : null,
                        !configuredName.isEmpty(), KspSymbols.parameterKind(canonical, resolver),
                        Boolean.TRUE.equals(config.get("required"))));
                needsBundle |= !provider;
            } catch (KspSymbols.InvalidSymbol exception) {
                throw new KspSymbols.InvalidSymbol("@Autowired field [" + KspSymbols.qualifiedName(target)
                        + "." + field.getSimpleName().asString() + "]: " + exception.getMessage());
            }
        }
        fields.sort(Comparator.comparing(field -> field.name));
        String bundleSource = null;
        if (needsBundle) {
            Set<String> hierarchy = KspSymbols.hierarchy(target.asStarProjectedType());
            if (hierarchy.contains("android.app.Activity")) {
                bundleSource = "ACTIVITY";
            } else if (hierarchy.contains("android.app.Fragment")
                    || hierarchy.contains("android.support.v4.app.Fragment")
                    || hierarchy.contains("androidx.fragment.app.Fragment")) {
                bundleSource = "FRAGMENT";
            } else {
                throw new KspSymbols.InvalidSymbol("Fields injected from Intent/Bundle require an Activity or Fragment "
                        + "target [" + KspSymbols.qualifiedName(target) + "]. Provider-only injection can target any class.");
            }
        }
        String binaryName = KspSymbols.binaryName(target);
        String helper = binaryName.substring(className.packageName().length() + 1) + "$$ARouter$$Autowired";
        return new AutowiredModel(className, helper, bundleSource, fields);
    }

    private void validateField(KSPropertyDeclaration field) {
        String name = field.getSimpleName().asString();
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            throw invalid(field, "field name must be referenceable from generated Java");
        }
        if (!field.isMutable() || field.getModifiers().contains(Modifier.CONST)) {
            throw invalid(field, "field must be mutable; val/final/const fields cannot be injected");
        }
        if (UtilsKt.isPrivate(field)) {
            throw invalid(field, "private fields cannot be injected");
        }
        if (field.getModifiers().contains(Modifier.JAVA_STATIC)) {
            throw invalid(field, "static fields cannot be injected");
        }
        if (field.getExtensionReceiver() != null || field.isDelegated()) {
            throw invalid(field, "extension/delegated properties cannot be injected");
        }
        if (field.getOrigin() == Origin.KOTLIN) {
            boolean jvmField = KspSymbols.annotation(field, "kotlin.jvm.JvmField") != null;
            boolean lateinit = field.getModifiers().contains(Modifier.LATEINIT);
            if (!field.getHasBackingField() || (!jvmField && !lateinit)) {
                throw invalid(field, "ordinary Kotlin properties are unsupported; use mutable @JvmField or accessible lateinit");
            }
            if (lateinit && field.getSetter() != null
                    && field.getSetter().getModifiers().contains(Modifier.PRIVATE)) {
                throw invalid(field, "lateinit with a private setter has an inaccessible backing field; use an accessible setter");
            }
            // effectiveJavaModifiers marks every non-open Kotlin property FINAL.
            // isMutable(), not that modifier, decides whether its backing field is writable.
        } else if (field.getModifiers().contains(Modifier.FINAL)) {
            throw invalid(field, "final fields cannot be injected");
        }
    }

    private static KspSymbols.InvalidSymbol invalid(KSPropertyDeclaration field, String reason) {
        return new KspSymbols.InvalidSymbol("Unsupported @Autowired field [" + field.getSimpleName().asString()
                + "]: " + reason + ".");
    }

    @Override
    public void prepareFinish() {
        for (Map.Entry<String, String> entry : unresolved.entrySet()) {
            error("Cannot resolve @Autowired target [" + entry.getKey() + "]: " + entry.getValue()
                    + ". Referenced types must be on the classpath or generated by KSP in a later round.", null);
        }
    }

    @Override
    public void emitFinish() {
        // Helpers are isolating and generated as soon as their complete target resolves.
    }

    @Override
    public boolean hasErrors() {
        return failed;
    }

    @Override
    public void onError() {
        failed = true;
    }

    private void error(String message, KSNode node) {
        failed = true;
        logger.error("ARouter: " + message, node);
    }
}

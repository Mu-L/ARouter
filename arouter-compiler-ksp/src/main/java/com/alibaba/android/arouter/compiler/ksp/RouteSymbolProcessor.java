package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.KSPLogger;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSAnnotation;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSFile;
import com.google.devtools.ksp.symbol.KSNode;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeAlias;
import com.google.devtools.ksp.symbol.KSTypeArgument;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.KSTypeReference;
import com.google.devtools.ksp.symbol.KSValueArgument;
import com.squareup.javapoet.ClassName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.SourceVersion;

/**
 * Phase one supports route and provider registration. Source injection and interceptor
 * annotations fail explicitly; a precompiled dependency may still supply APT-generated
 * injection helpers, whose inherited parameter metadata is included here.
 */
final class RouteSymbolProcessor implements SymbolProcessor {
    private static final String ROUTE = "com.alibaba.android.arouter.facade.annotation.Route";
    private static final String AUTOWIRED = "com.alibaba.android.arouter.facade.annotation.Autowired";
    private static final String INTERCEPTOR = "com.alibaba.android.arouter.facade.annotation.Interceptor";
    private static final String PROVIDER = "com.alibaba.android.arouter.facade.template.IProvider";
    private static final String MODULE_OPTION = "AROUTER_MODULE_NAME";
    private final KSPLogger logger;
    private final RouteEmitter emitter;
    private final String module;
    private final boolean generateDocs;
    private final Map<String, RouteModel> routesByClass = new TreeMap<>();
    private final Map<String, String> unresolved = new TreeMap<>();
    private final Set<String> unsupportedReported = new HashSet<>();
    // Replaced with this round's files on every process invocation. No resolver or
    // declaration/type symbol is reused in a subsequent round.
    private List<KSFile> finalRoundFiles = Collections.emptyList();
    private boolean failed;

    RouteSymbolProcessor(SymbolProcessorEnvironment environment) {
        logger = environment.getLogger();
        emitter = new RouteEmitter(environment.getCodeGenerator());
        String configuredModule = environment.getOptions().get(MODULE_OPTION);
        module = configuredModule == null ? "" : configuredModule.replaceAll("[^0-9a-zA-Z_]+", "");
        generateDocs = "enable".equals(environment.getOptions().get("AROUTER_GENERATE_DOC"));
        if (module.isEmpty()) {
            error("Missing or invalid AROUTER_MODULE_NAME. Configure ksp { "
                    + "arg(\"AROUTER_MODULE_NAME\", project.name) }; "
                    + "the name must contain an ASCII letter, digit, or underscore.", null);
        } else if (!module.equals(configuredModule)) {
            logger.warn("ARouter: normalized AROUTER_MODULE_NAME [" + configuredModule
                    + "] to [" + module + "]; module names must remain unique after normalization.", null);
        }
    }

    @Override
    public List<KSAnnotated> process(Resolver resolver) {
        List<KSFile> files = new ArrayList<>();
        Iterator<KSFile> sourceFiles = resolver.getAllFiles().iterator();
        while (sourceFiles.hasNext()) {
            files.add(sourceFiles.next());
        }
        finalRoundFiles = files;
        rejectUnsupported(resolver, AUTOWIRED);
        rejectUnsupported(resolver, INTERCEPTOR);

        List<KSAnnotated> deferred = new ArrayList<>();
        Iterator<KSAnnotated> symbols = resolver.getSymbolsWithAnnotation(ROUTE, false).iterator();
        while (symbols.hasNext()) {
            KSAnnotated symbol = symbols.next();
            if (!(symbol instanceof KSClassDeclaration)) {
                error("@Route must annotate a class.", symbol);
                continue;
            }
            KSClassDeclaration declaration = (KSClassDeclaration) symbol;
            if (declaration.getContainingFile() == null) {
                continue;
            }
            String name = qualifiedName(declaration);
            if (name == null) {
                error("@Route cannot annotate a local or anonymous class.", declaration);
                continue;
            }
            // KSP may return a previously deferred declaration along with new symbols.
            if (routesByClass.containsKey(name)) {
                continue;
            }
            try {
                RouteModel route = parse(declaration, resolver);
                unresolved.remove(name);
                if (route != null) {
                    routesByClass.put(name, route);
                }
            } catch (UnresolvedType unresolvedType) {
                unresolved.put(name, unresolvedType.getMessage());
                deferred.add(declaration);
            }
        }
        return deferred;
    }

    @Override
    public void finish() {
        for (Map.Entry<String, String> entry : unresolved.entrySet()) {
            error("Cannot resolve types needed by @Route [" + entry.getKey() + "]: "
                    + entry.getValue() + ". Ensure the type is on the compilation classpath "
                    + "or generated by another KSP processor, not javac/KAPT in the same module.", null);
        }
        List<RouteModel> routes = new ArrayList<>(routesByClass.values());
        routes.sort((left, right) -> {
            int group = left.group.compareTo(right.group);
            return group == 0 ? left.path.compareTo(right.path) : group;
        });
        validateUniqueRegistrations(routes);
        if (failed) {
            return;
        }
        try {
            // Every output is aggregating: adding a route can change a group, root,
            // provider index or JSON document. Include all current source roots so
            // unrelated-to-annotated source edits and deleting the last route are safe.
            emitter.emit(module, generateDocs, routes,
                    new Dependencies(true, finalRoundFiles.toArray(new KSFile[0])));
        } catch (IOException exception) {
            error("Could not generate route tables: " + exception.getMessage()
                    + ". Run only one ARouter processor backend (KSP or APT/KAPT) per module.", null);
        }
    }

    @Override
    public void onError() {
        failed = true;
    }

    private void rejectUnsupported(Resolver resolver, String annotationName) {
        Iterator<KSAnnotated> symbols = resolver.getSymbolsWithAnnotation(annotationName, false).iterator();
        while (symbols.hasNext()) {
            KSAnnotated symbol = symbols.next();
            if (UtilsKt.getContainingFile(symbol) == null) {
                continue;
            }
            String key = annotationName + ":" + symbol.getLocation();
            if (unsupportedReported.add(key)) {
                error("ARouter KSP phase one supports @Route and provider registration only; "
                        + "@" + annotationName.substring(annotationName.lastIndexOf('.') + 1)
                        + " is not supported in this module yet. Keep this module on "
                        + "arouter-compiler with APT/KAPT. Do not enable both ARouter backends "
                        + "in one module. Precompiled dependencies may keep their APT-generated helpers.",
                        symbol);
            }
        }
    }

    private RouteModel parse(KSClassDeclaration declaration, Resolver resolver) {
        if (declaration.getClassKind() != ClassKind.CLASS) {
            error("@Route must annotate an Activity, Service, Fragment or IProvider class, "
                    + "not an interface, object or enum.", declaration);
            return null;
        }
        ClassName destination = accessibleClassName(declaration);
        if (destination == null) {
            return null;
        }
        Map<String, Object> annotation = annotation(declaration, ROUTE);
        if (annotation == null) {
            throw new UnresolvedType("the @Route annotation");
        }
        String path = string(annotation, "path");
        String group = string(annotation, "group");
        if (!path.startsWith("/")) {
            error("Invalid route path [" + path + "]: paths must begin with '/'.", declaration);
            return null;
        }
        if (group.isEmpty()) {
            int separator = path.indexOf('/', 1);
            if (separator <= 1) {
                error("Invalid route path [" + path
                        + "]: provide '/group/path' or an explicit nonempty group.", declaration);
                return null;
            }
            group = path.substring(1, separator);
        }
        if (!SourceVersion.isIdentifier("ARouter$$Group$$" + group)) {
            error("Invalid route group [" + group
                    + "]: it must form a valid Java identifier in the generated group class.", declaration);
            return null;
        }
        Set<String> hierarchy = hierarchy(declaration.asStarProjectedType());
        String type;
        if (hierarchy.contains("android.app.Activity")) {
            type = "ACTIVITY";
        } else if (hierarchy.contains("android.app.Fragment")
                || hierarchy.contains("android.support.v4.app.Fragment")
                || hierarchy.contains("androidx.fragment.app.Fragment")) {
            type = "FRAGMENT";
        } else if (hierarchy.contains(PROVIDER)) {
            type = "PROVIDER";
        } else if (hierarchy.contains("android.app.Service")) {
            type = "SERVICE";
        } else {
            error("The @Route is marked on unsupported class [" + qualifiedName(declaration)
                    + "]. Expected an Activity, Service, Fragment or IProvider subtype.", declaration);
            return null;
        }

        Map<String, RouteModel.Parameter> parameters = new TreeMap<>();
        if ("ACTIVITY".equals(type) || "FRAGMENT".equals(type)) {
            collectParameters(declaration, parameters, new HashSet<>(), resolver);
        }
        List<String> providerKeys = new ArrayList<>();
        List<String> prototypes = new ArrayList<>();
        if ("PROVIDER".equals(type)) {
            Iterator<KSTypeReference> supers = declaration.getSuperTypes().iterator();
            while (supers.hasNext()) {
                KSType direct = expand(supers.next().resolve());
                KSDeclaration directDeclaration = direct.getDeclaration();
                if (!(directDeclaration instanceof KSClassDeclaration)
                        || ((KSClassDeclaration) directDeclaration).getClassKind() != ClassKind.INTERFACE) {
                    continue;
                }
                KSClassDeclaration contract = (KSClassDeclaration) directDeclaration;
                prototypes.add(qualifiedName(contract));
                if (PROVIDER.equals(qualifiedName(contract))) {
                    providerKeys.add(binaryName(declaration));
                } else if (hierarchy(direct).contains(PROVIDER)) {
                    // Runtime navigation(Class) uses Class.getName(): erase type arguments
                    // and retain '$' for nested interfaces.
                    providerKeys.add(binaryName(contract));
                }
            }
        }
        return new RouteModel(destination, qualifiedName(declaration), path, group, type,
                string(annotation, "name"), integer(annotation, "priority", -1),
                integer(annotation, "extras", Integer.MIN_VALUE),
                parameters, providerKeys, prototypes);
    }

    private void collectParameters(KSClassDeclaration declaration,
            Map<String, RouteModel.Parameter> parameters, Set<String> visited, Resolver resolver) {
        if (!visited.add(qualifiedName(declaration))) {
            return;
        }
        Iterator<KSDeclaration> declarations = declaration.getDeclarations().iterator();
        while (declarations.hasNext()) {
            KSDeclaration member = declarations.next();
            if (!(member instanceof KSPropertyDeclaration)) {
                continue;
            }
            Map<String, Object> config = annotation(member, AUTOWIRED);
            if (config == null) {
                continue;
            }
            KSPropertyDeclaration property = (KSPropertyDeclaration) member;
            KSType type = expand(property.getType().resolve());
            if (hierarchy(type).contains(PROVIDER)) {
                continue;
            }
            String name = string(config, "name");
            if (name.isEmpty()) {
                name = property.getSimpleName().asString();
            }
            parameters.put(name, new RouteModel.Parameter(parameterKind(type, resolver),
                    string(config, "desc"), Boolean.TRUE.equals(config.get("required"))));
        }
        // Match the APT compiler's subclass-then-superclass metadata precedence.
        Iterator<KSTypeReference> supers = declaration.getSuperTypes().iterator();
        while (supers.hasNext()) {
            KSDeclaration parent = expand(supers.next().resolve()).getDeclaration();
            if (parent instanceof KSClassDeclaration
                    && ((KSClassDeclaration) parent).getClassKind() == ClassKind.CLASS
                    && qualifiedName(parent) != null && !qualifiedName(parent).startsWith("android")) {
                collectParameters((KSClassDeclaration) parent, parameters, visited, resolver);
            }
        }
    }

    private int parameterKind(KSType type, Resolver resolver) {
        String name = qualifiedName(expand(type).getDeclaration());
        if (name != null) {
            switch (name) {
                case "kotlin.Boolean": case "java.lang.Boolean": return 0;
                case "kotlin.Byte": case "java.lang.Byte": return 1;
                case "kotlin.Short": case "java.lang.Short": return 2;
                case "kotlin.Int": case "java.lang.Integer": return 3;
                case "kotlin.Long": case "java.lang.Long": return 4;
                case "kotlin.Char": case "java.lang.Character": return 5;
                case "kotlin.Float": case "java.lang.Float": return 6;
                case "kotlin.Double": case "java.lang.Double": return 7;
                case "kotlin.String": case "java.lang.String": return 8;
                // Every JVM array is Serializable, even when Kotlin's source-level
                // array declaration does not list that Java superinterface.
                case "kotlin.Array":
                case "kotlin.BooleanArray": case "kotlin.ByteArray":
                case "kotlin.ShortArray": case "kotlin.IntArray":
                case "kotlin.LongArray": case "kotlin.CharArray":
                case "kotlin.FloatArray": case "kotlin.DoubleArray": return 9;
                default: break;
            }
        }
        if (isAssignableTo(type, "android.os.Parcelable", resolver)) {
            return 10;
        }
        if (isAssignableTo(type, "java.io.Serializable", resolver)) {
            return 9;
        }
        return 11;
    }

    private static boolean isAssignableTo(KSType type, String name, Resolver resolver) {
        KSClassDeclaration target = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name));
        return target != null && target.asStarProjectedType().isAssignableFrom(type.makeNotNullable());
    }

    private void validateUniqueRegistrations(List<RouteModel> routes) {
        Map<String, RouteModel> paths = new HashMap<>();
        Map<String, RouteModel> providerKeys = new HashMap<>();
        for (RouteModel route : routes) {
            RouteModel existing = paths.putIfAbsent(route.path, route);
            if (existing != null) {
                error("Duplicate route path [" + route.path + "] found on ["
                        + existing.qualifiedName + "] and [" + route.qualifiedName
                        + "]. Route priority is metadata and does not select between duplicate paths; "
                        + "use unique route paths.", null);
            }
            for (String key : route.providerKeys) {
                existing = providerKeys.putIfAbsent(key, route);
                if (existing != null && !existing.qualifiedName.equals(route.qualifiedName)) {
                    error("Duplicate provider lookup key [" + key + "] found on ["
                            + existing.qualifiedName + " at " + existing.path + "] and ["
                            + route.qualifiedName + " at " + route.path
                            + "]. Class-based provider navigation must have one implementation per key.",
                            null);
                }
            }
        }
    }

    private ClassName accessibleClassName(KSClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        KSDeclaration current = declaration;
        while (current != null) {
            if (!(current instanceof KSClassDeclaration)
                    || (!UtilsKt.isPublic(current) && !UtilsKt.isInternal(current))) {
                error("@Route destination [" + qualifiedName(declaration)
                        + "] and its enclosing classes must be accessible from the generated route package.",
                        declaration);
                return null;
            }
            String name = current.getSimpleName().asString();
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                error("@Route destination [" + qualifiedName(declaration)
                        + "] must have a name that can be referenced from generated Java.", declaration);
                return null;
            }
            names.add(0, name);
            current = current.getParentDeclaration();
        }
        String packageName = declaration.getPackageName().asString();
        if (packageName.isEmpty()) {
            error("@Route destination must belong to a named package.", declaration);
            return null;
        }
        for (String part : packageName.split("\\.")) {
            if (!SourceVersion.isIdentifier(part) || SourceVersion.isKeyword(part)) {
                error("@Route destination package must be accessible from generated Java.", declaration);
                return null;
            }
        }
        return ClassName.get(packageName, names.get(0),
                names.subList(1, names.size()).toArray(new String[0]));
    }

    private static String binaryName(KSClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        KSDeclaration current = declaration;
        while (current instanceof KSClassDeclaration) {
            names.add(0, current.getSimpleName().asString());
            current = current.getParentDeclaration();
        }
        return declaration.getPackageName().asString() + "." + String.join("$", names);
    }

    private static Set<String> hierarchy(KSType type) {
        Set<String> names = new LinkedHashSet<>();
        collectHierarchy(type, names, new HashSet<>());
        return names;
    }

    private static void collectHierarchy(KSType type, Set<String> names, Set<String> visited) {
        type = expand(type);
        KSDeclaration declaration = type.getDeclaration();
        String key = qualifiedName(declaration);
        if (key == null) {
            KSDeclaration owner = declaration.getParentDeclaration();
            key = (owner == null ? "" : qualifiedName(owner)) + ":" + declaration;
        }
        if (!visited.add(key)) {
            return;
        }
        names.add(key);
        if (declaration instanceof KSClassDeclaration) {
            Iterator<KSTypeReference> supers =
                    ((KSClassDeclaration) declaration).getSuperTypes().iterator();
            while (supers.hasNext()) {
                collectHierarchy(supers.next().resolve(), names, visited);
            }
        } else if (declaration instanceof KSTypeParameter) {
            Iterator<KSTypeReference> bounds = ((KSTypeParameter) declaration).getBounds().iterator();
            while (bounds.hasNext()) {
                collectHierarchy(bounds.next().resolve(), names, visited);
            }
        }
    }

    private static KSType expand(KSType type) {
        requireResolved(type, new HashSet<>());
        Set<String> aliases = new HashSet<>();
        while (type.getDeclaration() instanceof KSTypeAlias) {
            KSTypeAlias alias = (KSTypeAlias) type.getDeclaration();
            if (!aliases.add(qualifiedName(alias))) {
                throw new UnresolvedType("cyclic type alias " + alias.getSimpleName().asString());
            }
            type = alias.getType().resolve();
            requireResolved(type, new HashSet<>());
        }
        return type;
    }

    private static void requireResolved(KSType type, Set<String> visited) {
        if (type.isError()) {
            throw new UnresolvedType(type.toString());
        }
        if (!visited.add(type.toString())) {
            return;
        }
        if (type.getDeclaration() instanceof KSTypeAlias) {
            requireResolved(((KSTypeAlias) type.getDeclaration()).getType().resolve(), visited);
        }
        // In KSP2 a container may be valid while one of its type arguments is an error.
        // Star projections legitimately have no type reference.
        for (KSTypeArgument argument : type.getArguments()) {
            if (argument.getType() != null) {
                requireResolved(argument.getType().resolve(), visited);
            }
        }
    }

    private static Map<String, Object> annotation(KSAnnotated annotated, String name) {
        Iterator<KSAnnotation> annotations = annotated.getAnnotations().iterator();
        while (annotations.hasNext()) {
            KSAnnotation annotation = annotations.next();
            KSType type = annotation.getAnnotationType().resolve();
            if (type.isError()) {
                throw new UnresolvedType("annotation " + annotation.getShortName().asString());
            }
            if (name.equals(qualifiedName(type.getDeclaration()))) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (KSValueArgument argument : annotation.getArguments()) {
                    if (argument.getName() != null) {
                        result.put(argument.getName().asString(), argument.getValue());
                    }
                }
                return result;
            }
        }
        return null;
    }

    private static String qualifiedName(KSDeclaration declaration) {
        return declaration.getQualifiedName() == null ? null : declaration.getQualifiedName().asString();
    }

    private static String string(Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value instanceof String ? (String) value : "";
    }

    private static int integer(Map<String, Object> values, String name, int defaultValue) {
        Object value = values.get(name);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    private void error(String message, KSNode node) {
        failed = true;
        logger.error("ARouter: " + message, node);
    }

    private static final class UnresolvedType extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnresolvedType(String detail) {
            super(detail);
        }
    }
}

package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.KSPLogger;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSFile;
import com.google.devtools.ksp.symbol.KSNode;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeReference;
import com.squareup.javapoet.ClassName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.SourceVersion;
import com.alibaba.android.arouter.compiler.ksp.KspSymbols.UnresolvedType;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.annotation;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.binaryName;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.expand;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.hierarchy;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.integer;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.parameterKind;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.qualifiedName;
import static com.alibaba.android.arouter.compiler.ksp.KspSymbols.string;

/** Aggregates route/provider registries, including inherited injection metadata. */
final class RouteSymbolProcessor implements ManagedSymbolProcessor {
    private static final String ROUTE = "com.alibaba.android.arouter.facade.annotation.Route";
    private static final String AUTOWIRED = "com.alibaba.android.arouter.facade.annotation.Autowired";
    private static final String PROVIDER = "com.alibaba.android.arouter.facade.template.IProvider";
    private static final String MODULE_OPTION = "AROUTER_MODULE_NAME";
    private final KSPLogger logger;
    private final RouteEmitter emitter;
    private final String module;
    private final boolean generateDocs;
    private final Map<String, RouteModel> routesByClass = new TreeMap<>();
    private final Map<String, String> unresolved = new TreeMap<>();
    // Replaced with this round's files on every process invocation. No resolver or
    // declaration/type symbol is reused in a subsequent round.
    private List<KSFile> finalRoundFiles = Collections.emptyList();
    private boolean failed;
    private boolean prepared;
    private List<RouteModel> finalRoutes = Collections.emptyList();

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
    public void prepareFinish() {
        if (prepared) {
            return;
        }
        prepared = true;
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
        finalRoutes = routes;
    }

    @Override
    public boolean hasErrors() {
        return failed;
    }

    @Override
    public void emitFinish() {
        if (failed) {
            return;
        }
        try {
            // Every output is aggregating: adding a route can change a group, root,
            // provider index or JSON document. Include all current source roots so
            // unrelated-to-annotated source edits and deleting the last route are safe.
            emitter.emit(module, generateDocs, finalRoutes,
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
        try {
            KspSymbols.requireAccessible(declaration, RouteEmitter.PACKAGE);
            return KspSymbols.className(declaration);
        } catch (KspSymbols.InvalidSymbol exception) {
            error("@Route " + exception.getMessage(), declaration);
            return null;
        }
    }

    private void error(String message, KSNode node) {
        failed = true;
        logger.error("ARouter: " + message, node);
    }

}

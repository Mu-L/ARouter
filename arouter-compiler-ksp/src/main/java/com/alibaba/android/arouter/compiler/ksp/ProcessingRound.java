package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSFile;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.KSTypeReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Shared analysis scoped to one resolver round, never a persistent symbol cache. */
final class ProcessingRound {
    private final Map<KSDeclaration, Set<String>> hierarchies = new HashMap<>();
    private final Map<String, KSFile> files = new HashMap<>();

    void begin(Resolver resolver) {
        hierarchies.clear();
        files.clear();
        Iterator<KSFile> all = resolver.getAllFiles().iterator();
        while (all.hasNext()) {
            KSFile file = all.next();
            files.put(file.getFilePath(), file);
        }
    }

    void endAnalysis() {
        // Only the final round's source handles remain for CodeGenerator origins.
        hierarchies.clear();
    }

    void clear() {
        hierarchies.clear();
        files.clear();
    }

    KSFile[] origins(Set<String> paths) {
        KSFile[] result = new KSFile[paths.size()];
        int index = 0;
        for (String path : paths) {
            KSFile file = files.get(path);
            if (file == null) {
                throw new IllegalStateException("Missing current-round origin for ARouter output: " + path);
            }
            result[index++] = file;
        }
        return result;
    }

    Set<String> hierarchy(KSType type) {
        return hierarchy(type, new HashSet<>(), new boolean[1]);
    }

    boolean isProvider(KSType type) {
        return KspSymbols.intrinsicKind(type) < 0
                && hierarchy(type).contains("com.alibaba.android.arouter.facade.template.IProvider");
    }

    private Set<String> hierarchy(KSType type, Set<KSDeclaration> visiting, boolean[] cyclic) {
        // Validate the use-site arguments even on a cached raw declaration.
        // An unresolved Foo<Missing> must not reuse the success of Foo<String>.
        type = KspSymbols.expand(type);
        KSDeclaration declaration = type.getDeclaration();
        Set<String> cached = hierarchies.get(declaration);
        if (cached != null) {
            return cached;
        }
        String name = KspSymbols.qualifiedName(declaration);
        if (name == null) {
            KSDeclaration owner = declaration.getParentDeclaration();
            name = (owner == null ? "" : KspSymbols.qualifiedName(owner)) + ":" + declaration;
        }
        // Callers only ask for routing/component membership. Once a known
        // framework root is reached, resolving its implementation ancestry
        // cannot discover a different ARouter component kind or user provider.
        // Source subclasses' other directly declared interfaces are still read.
        if (terminal(name)) {
            Set<String> result = Collections.singleton(name);
            hierarchies.put(declaration, result);
            return result;
        }
        if (!visiting.add(declaration)) {
            // Cyclic bounds are not memoized; ordinary compilation diagnoses
            // illegal inheritance. Keep this traversal finite.
            cyclic[0] = true;
            return Collections.singleton(name);
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(name);
        try {
            Iterator<KSTypeReference> parents = declaration instanceof KSClassDeclaration
                    ? ((KSClassDeclaration) declaration).getSuperTypes().iterator()
                    : declaration instanceof KSTypeParameter
                    ? ((KSTypeParameter) declaration).getBounds().iterator()
                    : Collections.<KSTypeReference>emptyList().iterator();
            while (parents.hasNext()) {
                // These references belong to the declaration itself. Resolve
                // them once to preserve its source-to-supertype lookup edges;
                // each caller still resolves its own reference to this type.
                names.addAll(hierarchy(parents.next().resolve(), visiting, cyclic));
            }
            Set<String> result = Collections.unmodifiableSet(names);
            if (!cyclic[0]) {
                hierarchies.put(declaration, result);
            }
            return result;
        } finally {
            visiting.remove(declaration);
        }
    }

    private static boolean terminal(String name) {
        switch (name) {
            case "android.app.Activity":
            case "android.app.Service":
            case "android.app.Fragment":
            case "android.support.v4.app.Fragment":
            case "androidx.fragment.app.Fragment":
            case "com.alibaba.android.arouter.facade.template.IProvider":
            case "java.lang.Object":
            case "kotlin.Any":
                return true;
            default:
                return false;
        }
    }
}

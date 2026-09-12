package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.*;
import com.squareup.javapoet.ClassName;
import java.util.*;
import javax.lang.model.SourceVersion;

/** Shared symbol operations. Resolved symbols are used only within their current round. */
final class KspSymbols {
    private KspSymbols() { }

    static int parameterKind(KSType type, Resolver resolver) {
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

    static boolean isAssignableTo(KSType type, String name, Resolver resolver) {
        KSClassDeclaration target = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name));
        return target != null && target.asStarProjectedType().isAssignableFrom(type.makeNotNullable());
    }

    static String binaryName(KSClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        KSDeclaration current = declaration;
        while (current instanceof KSClassDeclaration) {
            names.add(0, current.getSimpleName().asString());
            current = current.getParentDeclaration();
        }
        return declaration.getPackageName().asString() + "." + String.join("$", names);
    }

    static Set<String> hierarchy(KSType type) {
        Set<String> names = new LinkedHashSet<>();
        collectHierarchy(type, names, new HashSet<>());
        return names;
    }

    static void collectHierarchy(KSType type, Set<String> names, Set<String> visited) {
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

    static KSType expand(KSType type) {
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

    static void requireResolved(KSType type, Set<String> visited) {
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

    static Map<String, Object> annotation(KSAnnotated annotated, String name) {
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

    static String qualifiedName(KSDeclaration declaration) {
        return declaration.getQualifiedName() == null ? null : declaration.getQualifiedName().asString();
    }

    static String string(Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value instanceof String ? (String) value : "";
    }

    static int integer(Map<String, Object> values, String name, int defaultValue) {
        Object value = values.get(name);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    static final class UnresolvedType extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnresolvedType(String detail) {
            super(detail);
        }
    }

    static void requireResolved(KSType type) {
        requireResolved(type, new HashSet<>());
    }

    static ClassName className(KSClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        KSDeclaration current = declaration;
        while (current != null) {
            if (!(current instanceof KSClassDeclaration) || current.getQualifiedName() == null) {
                throw new InvalidSymbol("Annotated target must be a named, non-local class.");
            }
            String name = current.getSimpleName().asString();
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                throw new InvalidSymbol("Annotated target [" + qualifiedName(declaration)
                        + "] must have a name that can be referenced from generated Java.");
            }
            names.add(0, name);
            current = current.getParentDeclaration();
        }
        String packageName = declaration.getPackageName().asString();
        if (packageName.isEmpty()) {
            throw new InvalidSymbol("Annotated target must belong to a named package.");
        }
        for (String part : packageName.split("\\.")) {
            if (!SourceVersion.isIdentifier(part) || SourceVersion.isKeyword(part)) {
                throw new InvalidSymbol("Annotated target package must be accessible from generated Java.");
            }
        }
        return ClassName.get(packageName, names.get(0), names.subList(1, names.size()).toArray(new String[0]));
    }

    static void requireAccessible(KSClassDeclaration declaration, String generatedPackage) {
        KSDeclaration current = declaration;
        while (current != null) {
            boolean samePackage = current.getPackageName().asString().equals(generatedPackage);
            if (!(current instanceof KSClassDeclaration) || UtilsKt.isPrivate(current)
                    || (!samePackage && !UtilsKt.isPublic(current) && !UtilsKt.isInternal(current))) {
                throw new InvalidSymbol("Annotated target [" + qualifiedName(declaration)
                        + "] and its enclosing classes must be accessible from generated package ["
                        + generatedPackage + "].");
            }
            current = current.getParentDeclaration();
        }
    }

    static final class InvalidSymbol extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidSymbol(String message) {
            super(message);
        }
    }
}

package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSName;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeAlias;
import com.google.devtools.ksp.symbol.KSTypeArgument;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.Modifier;
import com.google.devtools.ksp.symbol.Origin;
import com.google.devtools.ksp.symbol.Variance;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.WildcardTypeName;
import java.util.ArrayList;
import java.util.List;

/** Java type names for concrete JVM fields, preserving generic serialization tokens. */
final class KspTypeNames {
    private KspTypeNames() { }

    static KSType canonicalType(KSPropertyDeclaration field, Resolver resolver) {
        KSType original = field.getType().resolve();
        KspSymbols.requireResolved(original);
        if (field.getOrigin() == Origin.JAVA || field.getOrigin() == Origin.JAVA_LIB) {
            // Java already spells its wildcard projections. KSP's Kotlin wildcard
            // conversion cannot consume Java flexible/platform types such as String!.
            return original;
        }
        try {
            // KSP performs alias substitution and declaration/use-site wildcard mapping.
            // Expanding alias.declaration.type ourselves would lose applied arguments.
            KSType canonical = resolver.getJavaWildcard(field.getType()).resolve();
            KspSymbols.requireResolved(canonical);
            return canonical;
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new KspSymbols.InvalidSymbol("KSP cannot map this Kotlin field's inferred/platform "
                    + "type to Java. Declare an explicit Kotlin field type, including nullability "
                    + "and concrete type arguments. Details: " + exception.getMessage());
        }
    }

    static TypeName fieldType(KSPropertyDeclaration field, KSType canonical, Resolver resolver) {
        TypeName result = typeName(canonical, resolver, field.getPackageName().asString());
        String descriptor = resolver.mapToJvmSignature(field);
        if (descriptor == null || descriptor.contains("<ERROR>")) {
            throw new KspSymbols.UnresolvedType("JVM field signature of " + field.getSimpleName().asString());
        }
        TypeName primitive = primitiveDescriptor(descriptor);
        return primitive == null ? result : primitive;
    }

    static ClassName rawClass(KSType type, Resolver resolver) {
        KSType expanded = KspSymbols.expand(type);
        if (!(expanded.getDeclaration() instanceof KSClassDeclaration)) {
            throw new KspSymbols.InvalidSymbol("@Autowired provider fields require a concrete class or interface type.");
        }
        return className((KSClassDeclaration) expanded.getDeclaration(), resolver);
    }

    private static TypeName typeName(KSType type, Resolver resolver, String generatedPackage) {
        KspSymbols.requireResolved(type);
        KSDeclaration declaration = type.getDeclaration();
        if (declaration instanceof KSTypeAlias) {
            KSType expanded;
            try {
                expanded = resolver.getJavaWildcard(
                        resolver.createKSTypeReferenceFromKSType(type)).resolve();
            } catch (IllegalStateException | IllegalArgumentException exception) {
                throw new KspSymbols.InvalidSymbol("KSP could not expand generic alias ["
                        + KspSymbols.qualifiedName(declaration) + "]. Use its explicit concrete field type. "
                        + "Details: " + exception.getMessage());
            }
            if (expanded.getDeclaration() instanceof KSTypeAlias) {
                throw new KspSymbols.InvalidSymbol("KSP could not expand @Autowired generic type alias ["
                        + KspSymbols.qualifiedName(declaration) + "]. Use its concrete expanded field type.");
            }
            return typeName(expanded, resolver, generatedPackage);
        }
        if (declaration instanceof KSTypeParameter) {
            throw new KspSymbols.InvalidSymbol("@Autowired field types must be concrete; declaring-class "
                    + "type parameter [" + declaration.getSimpleName().asString()
                    + "] cannot be represented by a runtime serialization token. Use a concrete field type.");
        }
        if (!(declaration instanceof KSClassDeclaration)) {
            throw new KspSymbols.InvalidSymbol("Unsupported @Autowired field type [" + type + "].");
        }
        KspSymbols.requireAccessible((KSClassDeclaration) declaration, generatedPackage);
        if (declaration.getModifiers().contains(Modifier.VALUE)) {
            throw new KspSymbols.InvalidSymbol("@Autowired value-class fields are unsupported; "
                    + "use a regular JVM field type instead of [" + KspSymbols.qualifiedName(declaration) + "].");
        }
        String name = KspSymbols.qualifiedName(declaration);
        TypeName primitiveArray = primitiveArray(name);
        if (primitiveArray != null) {
            return ArrayTypeName.of(primitiveArray);
        }
        if ("kotlin.Array".equals(name)) {
            if (type.getArguments().isEmpty()
                    || type.getArguments().get(0).getVariance() == Variance.STAR
                    || type.getArguments().get(0).getType() == null) {
                return ArrayTypeName.of(TypeName.OBJECT);
            }
            // Arrays cannot have Java wildcard component types. JVM covariance is
            // represented by the component itself (or Object for an in-projection).
            KSTypeArgument component = type.getArguments().get(0);
            return ArrayTypeName.of(component.getVariance() == Variance.CONTRAVARIANT
                    ? TypeName.OBJECT : typeName(component.getType().resolve(), resolver, generatedPackage));
        }
        ClassName raw = className((KSClassDeclaration) declaration, resolver);
        if (resolver.isJavaRawType(type)) {
            return raw;
        }
        List<TypeName> arguments = new ArrayList<>();
        for (KSTypeArgument argument : type.getArguments()) {
            if (argument.getVariance() == Variance.STAR || argument.getType() == null) {
                arguments.add(WildcardTypeName.subtypeOf(TypeName.OBJECT));
                continue;
            }
            TypeName argumentName = typeName(argument.getType().resolve(), resolver, generatedPackage);
            if (argument.getVariance() == Variance.COVARIANT) {
                argumentName = WildcardTypeName.subtypeOf(argumentName);
            } else if (argument.getVariance() == Variance.CONTRAVARIANT) {
                argumentName = WildcardTypeName.supertypeOf(argumentName);
            }
            arguments.add(argumentName);
        }
        KSType outer = UtilsKt.getOuterType(type);
        if (outer != null) {
            TypeName outerName = typeName(outer, resolver, generatedPackage);
            if (outerName instanceof ParameterizedTypeName) {
                return ((ParameterizedTypeName) outerName).nestedClass(
                        declaration.getSimpleName().asString(), arguments);
            }
        }
        return arguments.isEmpty() ? raw
                : ParameterizedTypeName.get(raw, arguments.toArray(new TypeName[0]));
    }

    private static ClassName className(KSClassDeclaration declaration, Resolver resolver) {
        KSName qualifiedName = declaration.getQualifiedName();
        if (qualifiedName == null) {
            throw new KspSymbols.InvalidSymbol("@Autowired field type must have a qualified JVM name.");
        }
        KSName javaName = resolver.mapKotlinNameToJava(qualifiedName);
        return javaName == null ? KspSymbols.className(declaration) : ClassName.bestGuess(javaName.asString());
    }

    private static TypeName primitiveDescriptor(String descriptor) {
        switch (descriptor) {
            case "Z": return TypeName.BOOLEAN;
            case "B": return TypeName.BYTE;
            case "S": return TypeName.SHORT;
            case "I": return TypeName.INT;
            case "J": return TypeName.LONG;
            case "C": return TypeName.CHAR;
            case "F": return TypeName.FLOAT;
            case "D": return TypeName.DOUBLE;
            default: return null;
        }
    }

    private static TypeName primitiveArray(String name) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "kotlin.BooleanArray": return TypeName.BOOLEAN;
            case "kotlin.ByteArray": return TypeName.BYTE;
            case "kotlin.ShortArray": return TypeName.SHORT;
            case "kotlin.IntArray": return TypeName.INT;
            case "kotlin.LongArray": return TypeName.LONG;
            case "kotlin.CharArray": return TypeName.CHAR;
            case "kotlin.FloatArray": return TypeName.FLOAT;
            case "kotlin.DoubleArray": return TypeName.DOUBLE;
            default: return null;
        }
    }
}

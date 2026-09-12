package com.alibaba.android.arouter.kspfixture;

import android.os.Bundle;
import com.alibaba.android.arouter.launcher.ARouter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/** Assertions run inside the shrunk consumer application, using its real injection service. */
public final class InjectionChecks {
    private InjectionChecks() {}

    public static void run() {
        JavaFragment empty = javaFragment();
        empty.setArguments(null);
        ARouter.getInstance().inject(empty);
        checkDefaults(empty);
        empty.setArguments(new Bundle());
        ARouter.getInstance().inject(empty);
        checkDefaults(empty);
        empty.getArguments().putSerializable("count", null);
        ARouter.getInstance().inject(empty);
        checkDefaults(empty);

        JavaFragment supplied = (JavaFragment) ARouter.getInstance().build("/ksp/java-fragment")
                .withObject("payloads", Collections.singletonList(new Payload("java-wire"))).navigation();
        Bundle values = supplied.getArguments();
        values.putInt("count", 23);
        values.putSerializable("boxed", null);
        values.putString("text", null);
        values.putInt("inherited", 31);
        values.putSerializable("serializable", new ArrayList<>(Arrays.asList("one", "two")));
        Bundle nested = new Bundle();
        nested.putString("value", "parcelable");
        values.putParcelable("parcelable", nested);
        ARouter.getInstance().inject(supplied);
        check(supplied.count == 23 && supplied.boxed == null && supplied.text == null, "Java null/value injection");
        check(supplied.inherited == 31, "KAPT parent helper injection");
        check("java-wire".equals(supplied.payloads.get(0).value), "Java generic serialization");
        check(supplied.serializable.equals(Arrays.asList("one", "two")), "Serializable injection");
        check("parcelable".equals(supplied.parcelable.getString("value")), "Parcelable injection");
        checkProviders(supplied);
        values.putString("payloads", "null");
        ARouter.getInstance().inject(supplied);
        check("java-wire".equals(supplied.payloads.get(0).value), "Null parser result preserves default");

        KotlinFragment kotlin = (KotlinFragment) ARouter.getInstance().build("/ksp/kotlin-fragment").navigation();
        kotlin.setArguments(null);
        ARouter.getInstance().inject(kotlin);
        check(kotlin.kotlinCount == 11 && kotlin.kotlinBoxed == 13
                && "kotlin-default".equals(kotlin.kotlinText) && !kotlin.isLateTextInitialized(), "Kotlin missing defaults");
        checkDefaults(kotlin);
        Bundle kotlinValues = new Bundle();
        kotlinValues.putInt("kotlinCount", 37);
        kotlinValues.putSerializable("kotlinBoxed", null);
        kotlinValues.putString("kotlinText", null);
        kotlinValues.putString("lateText", "initialized");
        kotlinValues.putString("kotlinPayloads", "[\"kotlin-wire\"]");
        kotlinValues.putInt("count", 41);
        kotlinValues.putInt("inherited", 43);
        kotlin.setArguments(kotlinValues);
        ARouter.getInstance().inject(kotlin);
        check(kotlin.kotlinCount == 37 && kotlin.kotlinBoxed == null && kotlin.kotlinText == null, "Kotlin null/value injection");
        check("initialized".equals(kotlin.getLateText()), "Kotlin lateinit injection");
        check("kotlin-wire".equals(kotlin.kotlinPayloads.get(0).value), "Kotlin generic serialization");
        check(kotlin.count == 41 && kotlin.inherited == 43, "KSP and KAPT inherited helper chain");
        checkProviders(kotlin);

        InjectionFixtures.NestedFragment target = (InjectionFixtures.NestedFragment) ARouter.getInstance()
                .build("/ksp/nested").withString("nested", "binary-name").navigation();
        ARouter.getInstance().inject(target);
        check("binary-name".equals(target.nested), "Nested target JVM helper name");
        try {
            ARouter.getInstance().inject(new InjectionFixtures.MissingRequiredProvider());
            throw new AssertionError("Missing required provider did not fail");
        } catch (RuntimeException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("missing"), "Required provider diagnostic");
        }
    }

    private static JavaFragment javaFragment() {
        return (JavaFragment) ARouter.getInstance().build("/ksp/java-fragment").navigation();
    }
    private static void checkDefaults(JavaFragment target) {
        check(target.count == 7 && target.boxed == 9 && "java-default".equals(target.text), "Java missing defaults");
        check(target.inherited == 17, "Inherited default");
        check("java-default".equals(target.payloads.get(0).value), "Missing generic value preserves default");
        checkProviders(target);
    }
    private static void checkProviders(JavaFragment target) {
        check(target.typedProvider != null && "ksp".equals(target.typedProvider.backend()), "Provider by type");
        check(target.namedProvider != null && "kapt".equals(target.namedProvider.backend()), "Provider by path");
    }
    public static void checkTrace(String trace) {
        check("java,kapt,kotlin".equals(trace), "Interceptor priority order: " + trace);
    }
    public static void check(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}

# ARouter KSP2 compiler — development build

This standalone build implements [#959](https://github.com/alibaba/ARouter/issues/959):
route/provider registration, field injection and interceptor tables using KSP2. It preserves the current runtime
annotation model, generated package, registration interfaces and Gradle registration
plugin. The root APT/KAPT build continues to use its existing toolchain.

**This is an unpublished development artifact.** Check the supported field
shapes and toolchain below before migrating a module. Use one ARouter processor
backend per module; KSP modules can depend on precompiled APT/KAPT modules.

支持 Java/Kotlin 路由、Provider、Autowired、Interceptor 及可选路由文档。
Kotlin 注入支持可写的 `@JvmField` 和可访问的 `lateinit` 字段；普通属性、不可写字段、
未绑定类型参数等不安全形态会明确报错。预编译 APT/KAPT 依赖及其父类注入辅助类可以继续使用。
同一模块只能选择一种 ARouter 处理后端。

## Build and use locally

The standalone compiler build uses Gradle 9.5.0. Consumer verification selects
a separate wrapper for each exact Android toolchain:

| AGP | Consumer Gradle | Kotlin integration | Device matrix |
| --- | --- | --- | --- |
| 8.12.0 | 8.13 | kotlin-android / kotlin-kapt | API 34 |
| 9.0.0 | 9.1.0 | built-in Kotlin / legacy-kapt | API 34 |
| 9.3.2 | 9.5.0 | built-in Kotlin / legacy-kapt | API 21 and 34 |

All rows share:

| Component | Version |
| --- | --- |
| Build JDK | 17 |
| KSP | 2.3.12 |
| Kotlin | 2.3.20 |
| Consumer compile SDK / build tools | 36 / 36.0.0 |
| Consumer min SDK | 21 |
| Compiler and generated Java bytecode | Java 8 |

KSP 2.3.12 requires AGP 8.12.0 or newer according to its
[release notes](https://github.com/google/ksp/releases/tag/2.3.12).
The Gradle minimums come from the official [AGP 8.12](https://developer.android.com/build/releases/agp-8-12-0-release-notes)
and [AGP 9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
requirements. Kotlin 2.3.20's [fully supported KGP range](https://kotlinlang.org/docs/gradle-configure-project.html)
ends at AGP 9.0.0 / Gradle 9.3.0. The 9.3.2 / 9.5.0 row is an additional
project-tested integration, not a claim of vendor certification.
The fixture verifies exact rows, not every intervening AGP/Kotlin version.
The runtime library's minimum Android API is unchanged; the fixture is not a
minimum-API certification.

From the repository root, use JDK 8 to stage the current legacy artifacts:

    ./gradlew :arouter-annotation:installLocally :arouter-compiler:installLocally \
      :arouter-api:installLocally :arouter-gradle-plugin:installLocally

Then switch JAVA_HOME to JDK 17:

    ./arouter-compiler-ksp/gradlew -p arouter-compiler-ksp test installLocally

The compiler is staged as `com.alibaba:arouter-compiler-ksp:0.1.0-SNAPSHOT` in
`build/localMaven`. It has no configured remote publication repository.
Use a separate wrapper invocation for this build; adding it to the legacy root
settings or an included build does not isolate the Gradle/JDK requirements.

The executable example is [gradle/ksp-fixture](../gradle/ksp-fixture).
After resolving this local snapshot and applying the KSP plugin, its relevant
Groovy configuration is:

    ksp {
        arg('AROUTER_MODULE_NAME', 'my_feature')
        // Optional; JSON is written to the KSP resource output.
        arg('AROUTER_GENERATE_DOC', 'enable')
    }
    dependencies {
        ksp 'com.alibaba:arouter-compiler-ksp:0.1.0-SNAPSHOT'
    }

The runtime API dependency and local Maven repository are still required; see
the fixture's complete build files. The snapshot coordinate above is not a
published Maven Central dependency.

## Supported behavior and compatibility

- Routes on accessible Java/Kotlin Activity, Service, framework/support/AndroidX
  Fragment and IProvider classes generate Java Root, Group and Provider tables.
  Optional Fragment libraries need not all be present.
- Runtime entry points retain their existing names:
  `ARouter$$Root$$<module>`, `ARouter$$Group$$<group>` and
  `ARouter$$Providers$$<module>`, in `com.alibaba.android.arouter.routes`.
- Interceptors generate `ARouter$$Interceptors$$<module>`, with ascending integer
  priorities. Duplicate priorities fail within the processing module. Keep the
  legacy direct-IInterceptor implementation contract. Implementations must be
  accessible concrete classes with a public no-argument constructor; nonstatic
  Java member classes and Kotlin inner classes cannot satisfy that contract.
- Module names use the legacy ASCII letter/digit/underscore normalization and
  must remain nonempty and unique afterward. Route groups must form valid
  generated Java class names. Groups must also be unique across modules because
  the existing group class name does not include the module.
- Paths retain their original registration keys. Group-table metadata keeps the
  legacy lowercase path/group convention using Locale.ROOT. Provider-index
  metadata retains its original case. Priority and extras are preserved.
- A provider directly implementing IProvider is indexed by its implementation
  class. A directly declared provider subinterface is indexed by that interface.
  Lookup keys use erased JVM binary names, including `$` for nested types.
  As with APT, a provider inheriting its service interface only through a
  superclass is available by route path, without a newly inferred class key.
- Duplicate route paths fail regardless of group or priority. Ambiguous provider
  lookup keys also fail instead of depending on map insertion order. Both checks
  are scoped to the current processing module, not the whole application.
- Invalid destinations and unresolved types produce compilation diagnostics.
  Unresolved declarations are deferred for another KSP round; remaining errors
  fail the build. Types generated by javac/KAPT in the same compilation are not
  visible to KSP; migrate that generating processor or precompile its output in
  a dependency module.
- Injection helpers use the target's binary name plus `$$ARouter$$Autowired`;
  nested targets therefore generate names such as `Outer$Nested$$ARouter$$Autowired`.
  Each helper writes only its declaring class's fields. The existing runtime
  visits ancestors separately, allowing precompiled APT/KAPT parent helpers.
- Missing Intent/Bundle or missing keys preserve value-field defaults.
  Present null keys preserve primitive defaults and assign null to boxed,
  String, Parcelable and Serializable fields. Object parsing that returns null
  preserves the previous value. Missing serialization support is logged.
  Required missing providers throw; ordinary required reference values log when
  null. These are the current APT semantics.
- Provider fields support class lookup or the annotation's explicit route path.
  Concrete generic object fields retain their Java reflection type through
  TypeWrapper, including expanded generic aliases and supported wildcards.
- Existing annotated fields in precompiled parent classes contribute parameter
  metadata, including arrays, boxed values, Serializable/Parcelable and generic
  bounds. This compiler does not regenerate dependency injection helpers. Runtime
  injection still requires the dependency's existing helper and consumer rules.

Autowired accepts mutable Java instance fields accessible from the declaring
package, and mutable Kotlin `@JvmField` or accessible `lateinit` backing fields.
It rejects private/static/final/val/const fields, ordinary getter/setter properties,
delegated/extension properties and value-class fields. Use a concrete type rather
than a declaring-class type variable such as T, even if T has a Serializable bound.
This source-generation restriction is separate from reading inherited metadata.
If a Kotlin field infers a Java platform type, declare its Kotlin type and
nullability explicitly; KSP's wildcard conversion does not support that inferred
shape reliably.

KSP's new-language-feature opt-in is intentionally not enabled. Explicit
backing-field symbols need separate language-version coverage; the fixture pins
Kotlin 2.3 rather than claiming support for all future language features.

## Processing and dependency boundaries

Each round converts KSP symbols into compiler-private immutable route data.
The processor does not retain Resolver or declaration/type symbols between rounds.
It refreshes source file handles and validates all generators before emitting
aggregate tables once in `finish()`. Other KSP processors cannot consume
these final-round registries during their own processing.

Route and interceptor registries are aggregating. Their direct dependencies
include only the corresponding annotated source files; KSP traces referenced
parent and field types. Generated injectors are not added as registry origins.
Empty modules emit empty Root/Provider/Interceptor
registries. Each injection helper is isolating, with its declaring source as the
origin; the whole target must resolve before it is emitted. Incremental deletion
must remove obsolete Group and injector outputs. Inheritance analysis is reused
only within the current resolver round and is discarded before the next round;
use-site type arguments are still validated on every lookup.

JavaPoet and Gson are compiler dependencies. KSP API is compile-only and is
provided by the processing environment. None belongs on the Android application's
runtime classpath; the consumer verifier checks that boundary.

## Verification

The JVM regression suite runs the actual KSP engine, compiles generated Java and
Kotlin against this checkout's real annotation model and runtime interfaces, and
loads route tables. Its Android classes are analysis stubs; it does not replace
device tests. It covers mixed languages, nested/generic providers, type aliases,
metadata, diagnostics, generated/deferred injection targets, concrete type tokens,
interceptor ordering and precompiled field metadata. Wildcards, stars, raw types,
generic owners, arrays and Kotlin wildcard annotations are checked against the
compiled target field's actual JVM reflection type. Logs and generated sources are retained under
`arouter-compiler-ksp/build/reports/ksp-jvm`.

With JDK 17 and Android SDK 36 configured, run the independent Android consumer:

    ./gradle/verify-ksp.sh

Select a matrix row with AROUTER_AGP_VERSION; the script chooses and checks its
matching Gradle wrapper. With exactly one booted API 34 emulator:

    AROUTER_AGP_VERSION=8.12.0 AROUTER_RUN_DEVICE_TESTS=true ./gradle/verify-ksp.sh
    AROUTER_AGP_VERSION=9.0.0 AROUTER_RUN_DEVICE_TESTS=true ./gradle/verify-ksp.sh
    AROUTER_AGP_VERSION=9.3.2 AROUTER_RUN_DEVICE_TESTS=true ./gradle/verify-ksp.sh

For the current integration row on an API 21 emulator:

    AROUTER_EXPECT_API=21 AROUTER_RUN_DEVICE_TESTS=true ./gradle/verify-ksp.sh

The default row is AGP 9.3.2 and the default expected device API is 34.
An emulator with a different API fails the preflight instead of silently
substituting another device row. Each retained run includes gradle-version.log,
toolchain.json (observed AGP/KGP/KSP/JDK and applied plugins), and
matrix.properties (selected row and actual device API).

The verifier stages the current KSP compiler, checks Debug and Release/R8
assembly, configuration-cache reuse, incremental source changes, generated
registration bytecode and runtime dependencies. Device checks exercise Java and
Kotlin Activity navigation, Fragment creation/arguments, and provider path/class
lookup and interception across KSP and KAPT modules, plus default/null/generic
injection and inherited/nested helpers. It uses runtime consumer rules without
test-only keep rules. Isolated fixture copies and reports are preserved under
`build/reports/ksp-consumer`.

The [controlled KAPT/KSP benchmark](BENCHMARK.md) reports scenario-specific
results and raw-evidence hashes. It found mixed performance, not a universal
speedup. A subsequent [compile-time optimization](OPTIMIZATION.md) reduced
selected build costs while retaining byte-identical generated Java.
Release coordinates and remote publication are separate.

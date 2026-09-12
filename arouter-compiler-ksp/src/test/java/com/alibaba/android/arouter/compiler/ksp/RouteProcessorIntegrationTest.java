package com.alibaba.android.arouter.compiler.ksp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Runs the real KSP2 engine, then compiles and loads its Java output against this
 * checkout's annotation model and runtime interfaces. Android types here are
 * analysis stubs; actual navigation is covered by the separate Android fixture.
 */
public class RouteProcessorIntegrationTest {
    private static final String GENERATED_PACKAGE = "com.alibaba.android.arouter.routes.";
    private static final String SPI = "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider";
    private static Path repository;
    private static Path reports;
    private static Path model;
    private static Path minimalModel;
    private static Path javaHome;
    private static String kspClasspath;
    private static String kotlinClasspath;
    private static String processorClasspath;
    private Path work;

    @Rule public final TestName testName = new TestName();

    @BeforeClass
    public static void prepareRuntimeContract() throws Exception {
        repository = Paths.get(requiredProperty("arouter.repo.root"));
        reports = Paths.get("build", "reports", "ksp-jvm").toAbsolutePath();
        Files.createDirectories(reports);
        javaHome = Paths.get(System.getProperty("arouter.ksp.test.java.home", System.getProperty("java.home")));
        kspClasspath = requiredProperty("arouter.ksp.test.classpath");
        kotlinClasspath = System.getProperty("arouter.ksp.kotlin.classpath", kspClasspath);
        processorClasspath = requiredProperty("arouter.ksp.processor.classpath");
        model = compileModel(true);
        minimalModel = compileModel(false);
    }

    @Before
    public void prepareConsumer() throws IOException {
        work = Files.createTempDirectory(reports, testName.getMethodName() + "-");
        Files.createDirectories(work.resolve("src/java"));
        Files.createDirectories(work.resolve("src/kotlin"));
    }

    @Test
    public void compilesMixedLanguagesAndPreservesRuntimeMetadata() throws Exception {
        javaSource("fixture/JavaActivity.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route("
                + "path=\"/mixed/java\", priority=7, extras=42) "
                + "public class JavaActivity extends android.app.Activity {}");
        javaSource("fixture/NativeFragment.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/mixed/native\") "
                + "public class NativeFragment extends android.app.Fragment {}");
        javaSource("fixture/PlainService.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/mixed/service\") "
                + "public class PlainService extends android.app.Service {}");
        javaSource("fixture/ServiceProvider.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/mixed/provider\") "
                + "public class ServiceProvider extends android.app.Service "
                + "implements com.alibaba.android.arouter.facade.template.IProvider {"
                + "public void init(android.content.Context context) {}}");
        kotlinSource("fixture/KotlinRoutes.kt",
                "package fixture\n"
                + "import com.alibaba.android.arouter.facade.annotation.Route\n"
                + "class Outer {\n"
                + "  @Route(path=\"/mixed/nested\") class Nested : android.app.Activity()\n"
                + "}\n"
                + "@Route(path=\"/mixed/androidx\") class KotlinFragment : androidx.fragment.app.Fragment()\n");

        succeed(ksp(model, options("feature-pay"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Map<String, Object> routes = routes(loader, "featurepay");
            assertEquals(6, routes.size());
            assertRoute(routes, "/mixed/java", "fixture.JavaActivity", "ACTIVITY");
            assertRoute(routes, "/mixed/nested", "fixture.Outer$Nested", "ACTIVITY");
            assertRoute(routes, "/mixed/native", "fixture.NativeFragment", "FRAGMENT");
            assertRoute(routes, "/mixed/androidx", "fixture.KotlinFragment", "FRAGMENT");
            assertRoute(routes, "/mixed/service", "fixture.PlainService", "SERVICE");
            assertRoute(routes, "/mixed/provider", "fixture.ServiceProvider", "PROVIDER");
            assertEquals(7, property(routes.get("/mixed/java"), "getPriority"));
            assertEquals(42, property(routes.get("/mixed/java"), "getExtra"));
            assertEquals(Collections.singleton("fixture.ServiceProvider"), providers(loader, "featurepay").keySet());
        }
    }

    @Test
    public void providerKeysUseErasedJvmBinaryNamesAndResolveTypeAliases() throws Exception {
        kotlinSource("fixture/Providers.kt",
                "package fixture\n"
                + "import com.alibaba.android.arouter.facade.annotation.Route\n"
                + "import com.alibaba.android.arouter.facade.template.IProvider\n"
                + "class Services { interface Api<T> : IProvider }\n"
                + "typealias StringApi = Services.Api<String>\n"
                + "@Route(path=\"/provider/generic\") class GenericProvider : StringApi {\n"
                + "  override fun init(context: android.content.Context) {}\n"
                + "}\n"
                + "class Nested {\n"
                + " @Route(path=\"/provider/direct\") class Direct : IProvider {\n"
                + "   override fun init(context: android.content.Context) {}\n"
                + " }\n"
                + "}\n"
                + "open class BaseProvider : IProvider { override fun init(context: android.content.Context) {} }\n"
                + "@Route(path=\"/provider/inherited\") class InheritedProvider : BaseProvider()\n");
        succeed(ksp(model, options("providers"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Map<String, Object> indexed = providers(loader, "providers");
            assertEquals(2, indexed.size());
            assertEquals("fixture.GenericProvider", destination(indexed.get("fixture.Services$Api")));
            assertEquals("fixture.Nested$Direct", destination(indexed.get("fixture.Nested$Direct")));
            assertFalse(indexed.containsKey("fixture.InheritedProvider"));
            assertRoute(routes(loader, "providers"), "/provider/inherited",
                    "fixture.InheritedProvider", "PROVIDER");
        }
    }

    @Test
    public void providerOnlyInputDoesNotRequireOptionalAndroidClasses() throws Exception {
        javaSource("fixture/Standalone.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/only/provider\") "
                + "public class Standalone implements com.alibaba.android.arouter.facade.template.IProvider {"
                + "public void init(android.content.Context context) {}}");
        succeed(ksp(minimalModel, options("standalone"), false));
        try (URLClassLoader loader = compileConsumer(minimalModel)) {
            assertRoute(routes(loader, "standalone"), "/only/provider", "fixture.Standalone", "PROVIDER");
        }
    }

    @Test
    public void metadataCasingIsLocaleIndependentAndStringsAreEscaped() throws Exception {
        String path = "/TITLE/Item\"\\end";
        javaSource("fixture/Quoted.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=" + quote(path)
                + ", name=\"quoted route\", extras=9) public class Quoted extends android.app.Activity {}");
        Map<String, String> options = options("docs");
        options.put("AROUTER_GENERATE_DOC", "enable");
        succeed(ksp(model, options, false, "-Duser.language=tr", "-Duser.country=TR"));
        try (URLClassLoader loader = compileConsumer(model)) {
            Map<String, Object> routes = routes(loader, "docs");
            assertEquals(Collections.singleton(path), routes.keySet());
            assertEquals(path.toLowerCase(Locale.ROOT), property(routes.get(path), "getPath"));
            assertEquals("title", property(routes.get(path), "getGroup"));
        }
        Path docs = work.resolve("generated/resources/com/alibaba/android/arouter/docs/arouter-map-of-docs.json");
        JsonObject json = JsonParser.parseString(read(docs)).getAsJsonObject();
        assertTrue(json.has("TITLE"));
        assertEquals(path, json.getAsJsonArray("TITLE").get(0).getAsJsonObject().get("path").getAsString());
    }

    @Test
    public void explicitGroupAllowsSingleSegmentPath() throws Exception {
        javaSource("fixture/Explicit.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/single\", group=\"explicit\") "
                + "public class Explicit extends android.app.Activity {}");
        succeed(ksp(model, options("explicit"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            assertRoute(routes(loader, "explicit"), "/single", "fixture.Explicit", "ACTIVITY");
        }
    }

    @Test
    public void duplicatePathsFailAcrossGroupsAndPriorities() throws Exception {
        javaSource("fixture/First.java", activity("First", "/duplicate/path", "group=\"first\", priority=1"));
        javaSource("fixture/Second.java", activity("Second", "/duplicate/path", "group=\"second\", priority=2"));
        Result result = ksp(model, options("duplicates"), false);
        fails(result, "Duplicate route path");
        assertTrue(result.output, result.output.contains("fixture.First") && result.output.contains("fixture.Second"));
        assertNoRegistry();
    }

    @Test
    public void ambiguousProviderKeysFailInsteadOfOverwriting() throws Exception {
        javaSource("fixture/Api.java",
                "package fixture; public interface Api extends com.alibaba.android.arouter.facade.template.IProvider {}");
        for (String name : Arrays.asList("First", "Second")) {
            javaSource("fixture/" + name + ".java",
                    "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/providers/" + name + "\") "
                    + "public class " + name + " implements Api {public void init(android.content.Context c) {}}");
        }
        Result result = ksp(model, options("duplicates"), false);
        fails(result, "provider");
        assertTrue(result.output, result.output.contains("fixture.Api"));
        assertTrue(result.output, result.output.contains("fixture.First") && result.output.contains("fixture.Second"));
        assertNoRegistry();
    }

    @Test
    public void invalidModuleAndRouteInputsAreDiagnosed() throws Exception {
        javaSource("fixture/Page.java", activity("Page", "/valid/page", ""));
        fails(ksp(model, Collections.<String, String>emptyMap(), false), "AROUTER_MODULE_NAME");
        resetOutputs();
        fails(ksp(model, options("!!!"), false), "module");
        for (String path : Arrays.asList("missing/slash", "/missingGroup", "//page")) {
            resetOutputs();
            javaSource("fixture/Page.java", activity("Page", path, ""));
            fails(ksp(model, options("invalid"), false), "path");
        }
        resetOutputs();
        javaSource("fixture/Page.java", activity("Page", "/valid/page", "group=\"bad-group\""));
        fails(ksp(model, options("invalid"), false), "group");
    }

    @Test
    public void privateInjectionFieldsAndInvalidInterceptorsAreCompilationErrors() throws Exception {
        javaSource("fixture/Page.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/unsupported/page\") "
                + "public class Page extends android.app.Activity {"
                + "@com.alibaba.android.arouter.facade.annotation.Autowired private String title; }");
        fails(ksp(model, options("unsupported"), false), "private");
        assertNoRegistry();
        resetOutputs();
        javaSource("fixture/Page.java", activity("Page", "/supported/page", ""));
        javaSource("fixture/Intercept.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Interceptor(priority=1) "
                + "public class Intercept {}");
        fails(ksp(model, options("unsupported"), false), "Interceptor");
        assertNoRegistry();
    }

    @Test
    public void ordinaryKotlinPropertiesRequireAnExplicitJvmField() throws Exception {
        kotlinSource("fixture/Injected.kt",
                "package fixture\n"
                + "class Injected {\n"
                + "  @field:com.alibaba.android.arouter.facade.annotation.Autowired\n"
                + "  var title: String = \"default\"\n"
                + "}\n");
        fails(ksp(model, options("unsupported"), false), "JvmField");
        assertNoRegistry();
    }

    @Test
    public void unresolvedAndUnsupportedRouteTypesFailClearly() throws Exception {
        javaSource("fixture/Page.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/unresolved/page\") "
                + "public class Page extends missing.NoSuchBase {}");
        fails(ksp(model, options("unresolved"), false), "Cannot resolve types");
        assertNoRegistry();
        resetOutputs();
        javaSource("fixture/Page.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/unsupported/type\") "
                + "public class Page {}");
        fails(ksp(model, options("unsupported"), false), "unsupported");
    }

    @Test
    public void subsequentRoundRoutesAndDeferredSupertypesAreIncluded() throws Exception {
        javaSource("fixture/Initial.java", activity("Initial", "/round/initial", ""));
        javaSource("fixture/Deferred.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/round/deferred\") "
                + "public class Deferred extends late.GeneratedBase {}");
        succeed(ksp(model, options("rounds"), true));
        try (URLClassLoader loader = compileConsumer(model)) {
            Map<String, Object> routes = routes(loader, "rounds");
            assertEquals(3, routes.size());
            assertRoute(routes, "/round/generated", "late.GeneratedActivity", "ACTIVITY");
            assertRoute(routes, "/round/deferred", "fixture.Deferred", "ACTIVITY");
        }
    }

    @Test
    public void duplicateIntroducedByAnotherProcessorFails() throws Exception {
        javaSource("fixture/Initial.java", activity("Initial", "/round/initial", ""));
        Map<String, String> options = options("rounds");
        options.put("probe.generate", "duplicate");
        fails(ksp(model, options, true), "Duplicate route path");
        assertNoRegistry();
    }

    @Test
    public void precompiledAutowiredMetadataSurvivesMixedBackendBoundary() throws Exception {
        javaSource("fixture/Page.java",
                "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=\"/inherited/page\") "
                + "public class Page extends library.BaseActivity {}");
        succeed(ksp(model, options("inherited"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object meta = routes(loader, "inherited").get("/inherited/page");
            @SuppressWarnings("unchecked")
            Map<String, Integer> params = (Map<String, Integer>) property(meta, "getParamsType");
            assertEquals(Integer.valueOf(3), params.get("count"));
            assertEquals(Integer.valueOf(8), params.get("title"));
            assertEquals(Integer.valueOf(9), params.get("objects"));
            assertEquals(Integer.valueOf(9), params.get("numbers"));
            assertEquals(Integer.valueOf(9), params.get("payload"));
            assertEquals(Integer.valueOf(10), params.get("dual"));
            assertEquals(Integer.valueOf(8), params.get("quoted\"key"));
            assertFalse(params.containsKey("provider"));
        }
    }

    @Test
    public void emptyModuleProducesUsableEmptyRegistries() throws Exception {
        javaSource("fixture/Empty.java", "package fixture; public class Empty {}");
        succeed(ksp(model, options("empty"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            assertTrue(routes(loader, "empty").isEmpty());
            assertTrue(providers(loader, "empty").isEmpty());
        }
    }

    @Test
    public void javaInjectionPreservesMissingDefaultsAndAcceptsExplicitNull() throws Exception {
        javaSource("fixture/Injected.java",
                "package fixture; import com.alibaba.android.arouter.facade.annotation.Autowired;"
                + "@com.alibaba.android.arouter.facade.annotation.Route(path=\"/injection/java\") "
                + "public class Injected extends android.app.Activity {"
                + "@Autowired public int count=7; @Autowired public Integer boxed=9;"
                + "@Autowired @org.jetbrains.annotations.NotNull public Integer enhancedBoxed=11;"
                + "@Autowired public String title=\"default\";"
                + "@Autowired public String[] values=new String[]{\"old\"};"
                + "@Autowired protected long protectedValue=2L; @Autowired int packageValue=3; }");
        succeed(ksp(model, options("injection"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object target = loader.loadClass("fixture.Injected").getConstructor().newInstance();
            inject(loader, target);
            assertEquals(7, field(target, "count"));
            assertEquals(9, field(target, "boxed"));
            assertEquals("default", field(target, "title"));
            Object bundle = bundle(loader);
            setExtras(loader, target, bundle);
            inject(loader, target);
            assertEquals(7, field(target, "count"));
            put(bundle, "count", null);
            put(bundle, "boxed", null);
            put(bundle, "enhancedBoxed", null);
            put(bundle, "title", null);
            put(bundle, "protectedValue", 23L);
            put(bundle, "packageValue", 31);
            String[] values = {"new"};
            put(bundle, "values", values);
            inject(loader, target);
            assertEquals(7, field(target, "count"));
            assertEquals(null, field(target, "boxed"));
            assertEquals(null, field(target, "enhancedBoxed"));
            assertEquals(null, field(target, "title"));
            assertEquals(23L, field(target, "protectedValue"));
            assertEquals(31, field(target, "packageValue"));
            assertTrue(values == field(target, "values"));
            put(bundle, "count", 42);
            inject(loader, target);
            assertEquals(42, field(target, "count"));
        }
    }

    @Test
    public void kotlinNestedInjectionHandlesLateinitBoxingAndGenericAliases() throws Exception {
        serializationFixture();
        kotlinSource("fixture/KotlinInjected.kt",
                "package fixture\n"
                + "import com.alibaba.android.arouter.facade.annotation.Autowired\n"
                + "typealias Values<T> = List<T>\n"
                + "class Owner {\n"
                + " class Screen : androidx.fragment.app.Fragment() {\n"
                + "  @field:Autowired @JvmField var count: Int = 7\n"
                + "  @field:Autowired @JvmField var boxed: Int? = 9\n"
                + "  @field:Autowired lateinit var title: String\n"
                + "  @field:Autowired @JvmField var values: Values<String> = listOf(\"old\")\n"
                + " }\n"
                + "}\n");
        succeed(ksp(model, options("nested"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object target = loader.loadClass("fixture.Owner$Screen").getConstructor().newInstance();
            inject(loader, target); // Must not invoke an uninitialized lateinit getter.
            assertEquals(null, field(target, "title"));
            Object serializer = serializer(loader);
            List<String> parsed = Arrays.asList("new", "value");
            serializer.getClass().getField("value").set(serializer, parsed);
            Object bundle = bundle(loader);
            put(bundle, "count", 12);
            put(bundle, "boxed", null);
            put(bundle, "title", "ready");
            put(bundle, "values", "json");
            setExtras(loader, target, bundle);
            inject(loader, target);
            assertEquals(12, field(target, "count"));
            assertEquals(null, field(target, "boxed"));
            assertEquals("ready", field(target, "title"));
            assertTrue(parsed == field(target, "values"));
            java.lang.reflect.Type captured = (java.lang.reflect.Type) field(serializer, "lastType");
            assertEquals(target.getClass().getField("values").getGenericType().getTypeName(), captured.getTypeName());
            assertNotNull(loader.loadClass("fixture.Owner$Screen$$ARouter$$Autowired"));
        }
    }

    @Test
    public void genericObjectInjectionPreservesDefaultsOnMissingSerializerOrNullParse() throws Exception {
        serializationFixture();
        javaSource("fixture/Payload.java", "package fixture; public class Payload {}");
        javaSource("fixture/Objects.java",
                "package fixture; public class Objects extends androidx.fragment.app.Fragment {"
                + "@com.alibaba.android.arouter.facade.annotation.Autowired "
                + "public java.util.List<Payload> items=new java.util.ArrayList<>(); }");
        succeed(ksp(model, options("objects"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object target = loader.loadClass("fixture.Objects").getConstructor().newInstance();
            Object initial = field(target, "items");
            Object bundle = bundle(loader);
            put(bundle, "items", "json");
            setExtras(loader, target, bundle);
            inject(loader, target);
            assertTrue(initial == field(target, "items"));
            assertTrue(logErrors(loader).toString(), logErrors(loader).toString().contains("SerializationService"));
            Object serializer = serializer(loader);
            inject(loader, target); // The parser initially returns null.
            assertTrue(initial == field(target, "items"));
            List<Object> parsed = Arrays.asList(loader.loadClass("fixture.Payload").getConstructor().newInstance());
            serializer.getClass().getField("value").set(serializer, parsed);
            inject(loader, target);
            assertTrue(parsed == field(target, "items"));
            java.lang.reflect.ParameterizedType type = (java.lang.reflect.ParameterizedType) field(serializer, "lastType");
            assertEquals(List.class, type.getRawType());
            assertEquals(loader.loadClass("fixture.Payload"), type.getActualTypeArguments()[0]);
            assertEquals("json", field(serializer, "lastInput"));
            serializer.getClass().getField("value").set(serializer, null);
            inject(loader, target);
            assertTrue(parsed == field(target, "items"));
        }
    }

    @Test
    public void providerInjectionSupportsClassPathAndRequiredFailure() throws Exception {
        javaSource("fixture/Api.java",
                "package fixture; public interface Api extends com.alibaba.android.arouter.facade.template.IProvider {}");
        javaSource("fixture/Service.java",
                "package fixture; public class Service implements Api {public void init(android.content.Context c) {}}");
        javaSource("fixture/Holder.java",
                "package fixture; import com.alibaba.android.arouter.facade.annotation.Autowired; public class Holder {"
                + "@Autowired public Api typed; @Autowired(name=\"/custom/provider\") public Api named;"
                + "@Autowired(name=\"/missing\") public Api optional=new Service(); }");
        javaSource("fixture/Required.java",
                "package fixture; public class Required {"
                + "@com.alibaba.android.arouter.facade.annotation.Autowired(required=true) public Api service; }");
        succeed(ksp(model, options("services"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object target = loader.loadClass("fixture.Holder").getConstructor().newInstance();
            Object service = loader.loadClass("fixture.Service").getConstructor().newInstance();
            providerTypes(loader).put(loader.loadClass("fixture.Api"), service);
            providerPaths(loader).put("/custom/provider", service);
            inject(loader, target);
            assertTrue(service == field(target, "typed"));
            assertTrue(service == field(target, "named"));
            assertEquals(null, field(target, "optional"));
            providerTypes(loader).clear();
            Object required = loader.loadClass("fixture.Required").getConstructor().newInstance();
            try {
                inject(loader, required);
                throw new AssertionError("Missing required provider must fail");
            } catch (java.lang.reflect.InvocationTargetException error) {
                assertTrue(error.getCause() instanceof RuntimeException);
                assertTrue(error.getCause().getMessage().contains("service"));
            }
        }
    }

    @Test
    public void requiredValuesLogAndPrimitiveBundleGettersKeepTheirDefaults() throws Exception {
        javaSource("fixture/Primitives.java",
                "package fixture; import com.alibaba.android.arouter.facade.annotation.Autowired;"
                + "public class Primitives extends android.app.Fragment {"
                + "@Autowired(required=true) public String missing;"
                + "@Autowired(required=true) public boolean bool=true;"
                + "@Autowired public byte tiny=2; @Autowired public short small=3;"
                + "@Autowired public int number=4; @Autowired public long wide=5;"
                + "@Autowired public char letter='x'; @Autowired public float decimal=6.5f;"
                + "@Autowired public double precise=7.5; }");
        succeed(ksp(model, options("primitives"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object target = loader.loadClass("fixture.Primitives").getConstructor().newInstance();
            Object bundle = bundle(loader);
            setExtras(loader, target, bundle);
            for (String name : Arrays.asList("bool", "tiny", "small", "number", "wide", "letter", "decimal", "precise")) {
                put(bundle, name, null);
            }
            inject(loader, target);
            assertEquals(true, field(target, "bool"));
            assertEquals((byte) 2, field(target, "tiny"));
            assertEquals((short) 3, field(target, "small"));
            assertEquals(4, field(target, "number"));
            assertEquals(5L, field(target, "wide"));
            assertEquals('x', field(target, "letter"));
            assertEquals(6.5f, field(target, "decimal"));
            assertEquals(7.5d, field(target, "precise"));
            assertEquals(1, logErrors(loader).size());
            assertTrue(logErrors(loader).toString().contains("missing"));
        }
    }

    @Test
    public void invalidInjectionStorageAndUnboundTypesAreDiagnosed() throws Exception {
        String[] declarations = {
                "public static String value;", "public final String value=\"fixed\";",
                "private String value;", "public T value;"
        };
        for (int i = 0; i < declarations.length; i++) {
            resetOutputs();
            javaSource("fixture/Invalid.java",
                    "package fixture; public class Invalid<T> extends android.app.Activity {"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired " + declarations[i] + "}");
            Result result = ksp(model, options("invalid"), false);
            assertNotEquals(result.toString(), 0, result.exitCode);
            assertTrue(result.output, result.output.contains("value"));
            assertNoRegistry();
        }
    }

    @Test
    public void inferredKotlinPlatformFieldsRequestAnExplicitType() throws Exception {
        javaSource("fixture/JavaFactory.java",
                "package fixture; public class JavaFactory {public static String value() {return \"default\";} }");
        String source = "package fixture\n"
                + "class PlatformFields : android.app.Activity() {\n"
                + " @field:com.alibaba.android.arouter.facade.annotation.Autowired\n"
                + " @JvmField var value = JavaFactory.value()\n"
                + "}\n";
        kotlinSource("fixture/PlatformFields.kt", source);
        fails(ksp(model, options("platform"), false), "explicit");
        assertNoRegistry();
        resetOutputs();
        kotlinSource("fixture/PlatformFields.kt", source.replace("var value =", "var value: String ="));
        succeed(ksp(model, options("platform"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object target = loader.loadClass("fixture.PlatformFields").getConstructor().newInstance();
            Object bundle = bundle(loader);
            put(bundle, "value", "updated");
            setExtras(loader, target, bundle);
            inject(loader, target);
            assertEquals("updated", field(target, "value"));
        }
    }

    @Test
    public void injectionAndInterceptorsGeneratedInLaterRoundsAreComplete() throws Exception {
        serializationFixture();
        javaSource("fixture/DeferredInjected.java",
                "package fixture; import com.alibaba.android.arouter.facade.annotation.Autowired;"
                + "public class DeferredInjected extends android.app.Activity {"
                + "@Autowired public int count=7; @Autowired public late.GeneratedPayload payload; }");
        Map<String, String> options = options("deferred");
        options.put("probe.generate", "features");
        succeed(ksp(model, options, true));
        try (URLClassLoader loader = compileConsumer(model)) {
            Object serializer = serializer(loader);
            Object payload = loader.loadClass("late.GeneratedPayload").getConstructor().newInstance();
            serializer.getClass().getField("value").set(serializer, payload);
            Object target = loader.loadClass("fixture.DeferredInjected").getConstructor().newInstance();
            Object bundle = bundle(loader);
            put(bundle, "count", 11);
            put(bundle, "payload", "json");
            setExtras(loader, target, bundle);
            inject(loader, target);
            assertEquals(11, field(target, "count"));
            assertTrue(payload == field(target, "payload"));
            Object generated = loader.loadClass("late.GeneratedInjected").getConstructor().newInstance();
            setExtras(loader, generated, bundle);
            inject(loader, generated);
            assertEquals(11, field(generated, "count"));
            assertEquals("late.GeneratedInterceptor", interceptors(loader, "deferred").get(31).getName());
        }
    }

    @Test
    public void unresolvedInjectionFieldDoesNotProducePartialHelpersOrRegistries() throws Exception {
        javaSource("fixture/Unresolved.java",
                "package fixture; import com.alibaba.android.arouter.facade.annotation.Autowired;"
                + "public class Unresolved extends android.app.Activity {"
                + "@Autowired public int count=7; @Autowired public missing.Payload payload; }");
        fails(ksp(model, options("unresolved"), false), "Cannot resolve @Autowired");
        assertNoRegistry();
        assertFalse(Files.exists(work.resolve("generated/java/fixture/Unresolved$$ARouter$$Autowired.java")));
    }

    @Test
    public void interceptorRegistryOrdersJavaKotlinAndNestedImplementations() throws Exception {
        javaSource("fixture/JavaInterceptor.java", interceptor("JavaInterceptor", -10));
        kotlinSource("fixture/KotlinInterceptors.kt",
                "package fixture\n"
                + "import com.alibaba.android.arouter.facade.annotation.Interceptor\n"
                + "import com.alibaba.android.arouter.facade.template.IInterceptor\n"
                + "typealias Interception = IInterceptor\n"
                + "@Interceptor(priority=10) class KotlinInterceptor(val label: String = \"default\") : Interception {\n"
                + " override fun init(context: android.content.Context) {}\n"
                + " override fun process(postcard: com.alibaba.android.arouter.facade.Postcard,\n"
                + " callback: com.alibaba.android.arouter.facade.callback.InterceptorCallback) {callback.onContinue(postcard)}\n"
                + "}\n"
                + "class OuterInterceptors {\n"
                + " @Interceptor(priority=0) class Nested : IInterceptor {\n"
                + "  override fun init(context: android.content.Context) {}\n"
                + "  override fun process(postcard: com.alibaba.android.arouter.facade.Postcard,\n"
                + "  callback: com.alibaba.android.arouter.facade.callback.InterceptorCallback) {callback.onContinue(postcard)}\n"
                + " }\n"
                + "}\n");
        succeed(ksp(model, options("interception"), false));
        try (URLClassLoader loader = compileConsumer(model)) {
            Map<Integer, Class<?>> registry = interceptors(loader, "interception");
            assertEquals(Arrays.asList(-10, 0, 10), new ArrayList<>(registry.keySet()));
            assertEquals("fixture.JavaInterceptor", registry.get(-10).getName());
            assertEquals("fixture.OuterInterceptors$Nested", registry.get(0).getName());
            assertEquals("fixture.KotlinInterceptor", registry.get(10).getName());
            for (Class<?> implementation : registry.values()) {
                assertNotNull(implementation.getConstructor().newInstance());
            }
        }
    }

    @Test
    public void duplicateInterceptorPrioritiesPreventEveryAggregateRegistry() throws Exception {
        javaSource("fixture/Page.java", activity("Page", "/interceptor/page", ""));
        javaSource("fixture/First.java", interceptor("First", 1));
        javaSource("fixture/Second.java", interceptor("Second", 1));
        Result result = ksp(model, options("duplicate"), false);
        fails(result, "priority");
        assertTrue(result.output, result.output.contains("fixture.First") && result.output.contains("fixture.Second"));
        assertNoRegistry();
    }

    @Test
    public void invalidInterceptorConstructorsAndIndirectContractsAreDiagnosed() throws Exception {
        javaSource("fixture/Invalid.java", interceptor("Invalid", 1)
                .replace("public class Invalid", "public abstract class Invalid"));
        fails(ksp(model, options("invalid"), false), "concrete");
        resetOutputs();
        javaSource("fixture/Invalid.java", interceptor("Invalid", 1)
                .replace("public void init", "public Invalid(String required) {} public void init"));
        fails(ksp(model, options("invalid"), false), "constructor");
        resetOutputs();
        javaSource("fixture/Invalid.java",
                "package fixture; public enum Invalid { VALUE; "
                + "@com.alibaba.android.arouter.facade.annotation.Interceptor(priority=1)"
                + "public class Inner implements com.alibaba.android.arouter.facade.template.IInterceptor {"
                + "public void init(android.content.Context c) {}"
                + "public void process(com.alibaba.android.arouter.facade.Postcard p,"
                + "com.alibaba.android.arouter.facade.callback.InterceptorCallback c) {c.onContinue(p);} } }");
        fails(ksp(model, options("invalid"), false), "static");
        resetOutputs();
        javaSource("fixture/Invalid.java",
                "package fixture; public class Invalid {"
                + "public interface Indirect extends com.alibaba.android.arouter.facade.template.IInterceptor {}"
                + "@com.alibaba.android.arouter.facade.annotation.Interceptor(priority=1)"
                + "public static class Impl implements Indirect {"
                + "public void init(android.content.Context c) {}"
                + "public void process(com.alibaba.android.arouter.facade.Postcard p,"
                + "com.alibaba.android.arouter.facade.callback.InterceptorCallback c) {c.onContinue(p);} } }");
        fails(ksp(model, options("invalid"), false), "direct");
    }

    private static String interceptor(String name, int priority) {
        return "package fixture; @com.alibaba.android.arouter.facade.annotation.Interceptor(priority="
                + priority + ") public class " + name
                + " implements com.alibaba.android.arouter.facade.template.IInterceptor {"
                + "public void init(android.content.Context context) {}"
                + "public void process(com.alibaba.android.arouter.facade.Postcard postcard,"
                + "com.alibaba.android.arouter.facade.callback.InterceptorCallback callback) {callback.onContinue(postcard);} }";
    }

    private void serializationFixture() throws IOException {
        javaSource("fixture/Serializer.java",
                "package fixture; public class Serializer implements com.alibaba.android.arouter.facade.service.SerializationService {"
                + "public Object value; public java.lang.reflect.Type lastType; public String lastInput;"
                + "public void init(android.content.Context context) {}"
                + "public <T> T json2Object(String input, Class<T> type) {throw new UnsupportedOperationException();}"
                + "public String object2Json(Object input) {throw new UnsupportedOperationException();}"
                + "@SuppressWarnings(\"unchecked\") public <T> T parseObject(String input, java.lang.reflect.Type type) {"
                + "lastInput=input; lastType=type; return (T)value;} }");
    }

    private static Object serializer(ClassLoader loader) throws Exception {
        Object serializer = loader.loadClass("fixture.Serializer").getConstructor().newInstance();
        providerTypes(loader).put(loader.loadClass("com.alibaba.android.arouter.facade.service.SerializationService"), serializer);
        return serializer;
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object bundle(ClassLoader loader) throws Exception {
        return loader.loadClass("android.os.Bundle").getConstructor().newInstance();
    }

    private static void put(Object bundle, String key, Object value) throws Exception {
        bundle.getClass().getMethod("put", String.class, Object.class).invoke(bundle, key, value);
    }

    private static void setExtras(ClassLoader loader, Object target, Object bundle) throws Exception {
        if (loader.loadClass("android.app.Activity").isInstance(target)) {
            Object intent = loader.loadClass("android.content.Intent").getConstructor().newInstance();
            intent.getClass().getMethod("putExtras", bundle.getClass()).invoke(intent, bundle);
            target.getClass().getMethod("setIntent", intent.getClass()).invoke(target, intent);
        } else {
            target.getClass().getMethod("setArguments", bundle.getClass()).invoke(target, bundle);
        }
    }

    private static void inject(ClassLoader loader, Object target) throws Exception {
        Class<?> type = loader.loadClass(target.getClass().getName() + "$$ARouter$$Autowired");
        type.getMethod("inject", Object.class).invoke(type.getConstructor().newInstance(), target);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Object> providerTypes(ClassLoader loader) throws Exception {
        return (Map<Class<?>, Object>) loader.loadClass("com.alibaba.android.arouter.launcher.ARouter").getField("types").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> providerPaths(ClassLoader loader) throws Exception {
        return (Map<String, Object>) loader.loadClass("com.alibaba.android.arouter.launcher.ARouter").getField("paths").get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> logErrors(ClassLoader loader) throws Exception {
        return (List<String>) loader.loadClass("android.util.Log").getField("errors").get(null);
    }

    private static Map<Integer, Class<?>> interceptors(ClassLoader loader, String module) throws Exception {
        Object registry = loader.loadClass(GENERATED_PACKAGE + "ARouter$$Interceptors$$" + module)
                .getConstructor().newInstance();
        Map<Integer, Class<?>> interceptors = new LinkedHashMap<>();
        registry.getClass().getMethod("loadInto", Map.class).invoke(registry, interceptors);
        return interceptors;
    }

    private Result ksp(Path contract, Map<String, String> options, boolean companion, String... vmOptions) throws Exception {
        Path generated = work.resolve("generated");
        for (String directory : Arrays.asList("classes", "java", "kotlin", "resources", "caches")) {
            Files.createDirectories(generated.resolve(directory));
        }
        String processors = processorClasspath;
        if (companion) {
            Path services = work.resolve("companion-services");
            write(services.resolve(SPI), RoundGeneratingProvider.class.getName() + "\n");
            processors += File.pathSeparator + services + File.pathSeparator
                    + Paths.get(RoundGeneratingProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
        List<String> command = new ArrayList<>();
        command.add(javaHome.resolve("bin/java").toString());
        Collections.addAll(command, vmOptions);
        Collections.addAll(command, "-cp", kspClasspath, "com.google.devtools.ksp.cmdline.KSPJvmMain",
                "-jvm-target", "1.8", "-jdk-home", javaHome.toString(), "-module-name", "test",
                "-source-roots", work.resolve("src/kotlin").toString(),
                "-java-source-roots", work.resolve("src/java").toString(),
                "-libraries", contract + File.pathSeparator + kotlinClasspath,
                "-project-base-dir", work.toString(), "-output-base-dir", generated.toString(),
                "-caches-dir", generated.resolve("caches").toString(),
                "-class-output-dir", generated.resolve("classes").toString(),
                "-java-output-dir", generated.resolve("java").toString(),
                "-kotlin-output-dir", generated.resolve("kotlin").toString(),
                "-resource-output-dir", generated.resolve("resources").toString(),
                "-language-version", "2.3", "-api-version", "2.3");
        if (!options.isEmpty()) {
            command.add("-processor-options");
            command.add(options.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        command.add(processors);
        return execute(work, "ksp", command);
    }

    private URLClassLoader compileConsumer(Path contract) throws Exception {
        Path classes = work.resolve("consumer-classes");
        Files.createDirectories(classes);
        List<Path> javaSources = sources(work.resolve("src/java"), ".java");
        javaSources.addAll(sources(work.resolve("generated/java"), ".java"));
        List<Path> kotlinSources = sources(work.resolve("src/kotlin"), ".kt");
        kotlinSources.addAll(sources(work.resolve("generated/kotlin"), ".kt"));
        String classpath = contract + File.pathSeparator + classes + File.pathSeparator + kotlinClasspath;
        if (!kotlinSources.isEmpty()) {
            List<String> kotlin = new ArrayList<>(Arrays.asList(javaHome.resolve("bin/java").toString(),
                    "-cp", kotlinClasspath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", classpath,
                    "-d", classes.toString()));
            for (Path source : kotlinSources) kotlin.add(source.toString());
            for (Path source : javaSources) kotlin.add(source.toString());
            succeed(execute(work, "kotlinc", kotlin));
        }
        if (!javaSources.isEmpty()) {
            javac(work, "consumer-javac", classes, classpath, javaSources);
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL(), contract.toUri().toURL()},
                getClass().getClassLoader());
    }

    private static Path compileModel(boolean android) throws Exception {
        Path directory = Files.createTempDirectory(reports, android ? "runtime-contract-" : "provider-contract-");
        Path source = directory.resolve("src");
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        write(source.resolve("android/content/Context.java"), "package android.content; public class Context {}");
        write(source.resolve("android/os/Bundle.java"), bundleSource());
        write(source.resolve("android/util/Log.java"),
                "package android.util; public class Log {"
                + "public static final java.util.List<String> errors = new java.util.ArrayList<>();"
                + "public static int e(String tag, String message) {errors.add(message); return 0;} }");
        write(source.resolve("com/alibaba/android/arouter/facade/Postcard.java"),
                "package com.alibaba.android.arouter.facade; public class Postcard {"
                + "private final String path; private final android.os.Bundle extras = new android.os.Bundle();"
                + "public Postcard(String path) {this.path=path;} public String getPath() {return path;}"
                + "public android.os.Bundle getExtras() {return extras;}"
                + "public Object navigation() {return com.alibaba.android.arouter.launcher.ARouter.paths.get(path);} }");
        write(source.resolve("com/alibaba/android/arouter/launcher/ARouter.java"),
                "package com.alibaba.android.arouter.launcher; public class ARouter {"
                + "private static final ARouter INSTANCE=new ARouter();"
                + "public static final java.util.Map<Class<?>,Object> types=new java.util.HashMap<>();"
                + "public static final java.util.Map<String,Object> paths=new java.util.HashMap<>();"
                + "public static ARouter getInstance() {return INSTANCE;}"
                + "public <T> T navigation(Class<T> type) {return type.cast(types.get(type));}"
                + "public com.alibaba.android.arouter.facade.Postcard build(String path) {"
                + "return new com.alibaba.android.arouter.facade.Postcard(path);} }");
        if (android) {
            write(source.resolve("android/content/Intent.java"),
                    "package android.content; public class Intent {"
                    + "private android.os.Bundle extras; public android.os.Bundle getExtras() {return extras;}"
                    + "public Intent putExtras(android.os.Bundle extras) {this.extras=extras; return this;} }");
            write(source.resolve("android/app/Activity.java"),
                    "package android.app; public class Activity extends android.content.Context {"
                    + "private android.content.Intent intent;"
                    + "public android.content.Intent getIntent() {return intent;}"
                    + "public void setIntent(android.content.Intent intent) {this.intent=intent;} }");
            write(source.resolve("android/app/Service.java"),
                    "package android.app; public class Service extends android.content.Context {}");
            write(source.resolve("android/app/Fragment.java"), fragmentSource("android.app"));
            write(source.resolve("androidx/fragment/app/Fragment.java"),
                    fragmentSource("androidx.fragment.app"));
            write(source.resolve("android/os/Parcelable.java"), "package android.os; public interface Parcelable {}");
            write(source.resolve("library/Dual.java"),
                    "package library; public class Dual implements android.os.Parcelable, java.io.Serializable {}");
            write(source.resolve("library/BaseActivity.java"),
                    "package library; public class BaseActivity<T extends java.io.Serializable> extends android.app.Activity {"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired public int count;"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired public String title;"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired public String[] objects;"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired public int[] numbers;"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired public T payload;"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired public Dual dual;"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired(name=" + quote("quoted\"key") + ") public String quoted;"
                    + "@com.alibaba.android.arouter.facade.annotation.Autowired "
                    + "public com.alibaba.android.arouter.facade.template.IProvider provider; }");
        }
        List<Path> inputs = sources(repository.resolve("arouter-annotation/src/main/java"), ".java");
        for (String name : Arrays.asList("IRouteRoot", "IRouteGroup", "IProvider", "IProviderGroup",
                "ISyringe", "IInterceptor", "IInterceptorGroup")) {
            inputs.add(repository.resolve("arouter-api/src/main/java/com/alibaba/android/arouter/facade/template/"
                    + name + ".java"));
        }
        inputs.add(repository.resolve("arouter-api/src/main/java/com/alibaba/android/arouter/facade/service/SerializationService.java"));
        inputs.add(repository.resolve("arouter-api/src/main/java/com/alibaba/android/arouter/facade/callback/InterceptorCallback.java"));
        inputs.addAll(sources(source, ".java"));
        javac(directory, "contract-javac", classes, "", inputs);
        return classes;
    }

    private static String fragmentSource(String packageName) {
        return "package " + packageName + "; public class Fragment {"
                + "private android.os.Bundle arguments;"
                + "public android.os.Bundle getArguments() {return arguments;}"
                + "public void setArguments(android.os.Bundle arguments) {this.arguments=arguments;} }";
    }

    private static String bundleSource() {
        StringBuilder source = new StringBuilder("package android.os; public class Bundle {"
                + "private final java.util.Map<String,Object> values=new java.util.HashMap<>();"
                + "public boolean containsKey(String key) {return values.containsKey(key);}"
                + "public Object get(String key) {return values.get(key);}"
                + "public void put(String key,Object value) {values.put(key,value);}"
                + "public String getString(String key) {return (String)values.get(key);}"
                + "public void putString(String key,String value) {values.put(key,value);}");
        String[][] primitives = {{"boolean", "Boolean"}, {"byte", "Byte"}, {"short", "Short"},
                {"int", "Integer"}, {"long", "Long"}, {"char", "Character"},
                {"float", "Float"}, {"double", "Double"}};
        for (String[] type : primitives) {
            String suffix = Character.toUpperCase(type[0].charAt(0)) + type[0].substring(1);
            source.append("public ").append(type[0]).append(" get").append(suffix)
                    .append("(String key, ").append(type[0]).append(" fallback) {Object value=values.get(key);")
                    .append("return value instanceof ").append(type[1]).append(" ? (")
                    .append(type[1]).append(")value : fallback;}");
        }
        return source.append("}").toString();
    }

    private static void javac(Path directory, String label, Path output, String classpath, List<Path> sources)
            throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(javaHome.resolve("bin/javac").toString(),
                "--release", "8", "-proc:none", "-d", output.toString()));
        if (!classpath.isEmpty()) Collections.addAll(command, "-classpath", classpath);
        for (Path source : sources) command.add(source.toString());
        succeed(execute(directory, label, command));
    }

    private static Result execute(Path directory, String label, List<String> command) throws Exception {
        Path log = Files.createTempFile(directory, label + "-", ".log");
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(label + " timed out; output: " + log + "\n" + read(log));
        }
        return new Result(process.exitValue(), read(log), log);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> routes(ClassLoader loader, String module) throws Exception {
        Object root = loader.loadClass(GENERATED_PACKAGE + "ARouter$$Root$$" + module).getConstructor().newInstance();
        Map<String, Class<?>> groups = new LinkedHashMap<>();
        root.getClass().getMethod("loadInto", Map.class).invoke(root, groups);
        Map<String, Object> routes = new LinkedHashMap<>();
        for (Class<?> group : groups.values()) {
            Object instance = group.getConstructor().newInstance();
            group.getMethod("loadInto", Map.class).invoke(instance, routes);
        }
        return routes;
    }

    private static Map<String, Object> providers(ClassLoader loader, String module) throws Exception {
        Object registry = loader.loadClass(GENERATED_PACKAGE + "ARouter$$Providers$$" + module)
                .getConstructor().newInstance();
        Map<String, Object> providers = new LinkedHashMap<>();
        registry.getClass().getMethod("loadInto", Map.class).invoke(registry, providers);
        return providers;
    }

    private static void assertRoute(Map<String, Object> routes, String path, String destination, String type)
            throws Exception {
        Object meta = routes.get(path);
        assertNotNull("Missing route " + path + " in " + routes.keySet(), meta);
        assertEquals(destination, destination(meta));
        assertEquals(type, property(meta, "getType").toString());
    }

    private static Object property(Object object, String name) throws Exception {
        Method method = object.getClass().getMethod(name);
        return method.invoke(object);
    }

    private static String destination(Object meta) throws Exception {
        assertNotNull("Missing provider entry", meta);
        return ((Class<?>) property(meta, "getDestination")).getName();
    }

    private void assertNoRegistry() throws IOException {
        assertTrue("Unexpected route output after an error",
                sources(work.resolve("generated/java/com/alibaba/android/arouter/routes"), ".java").isEmpty());
    }

    private void resetOutputs() throws IOException {
        // Preserve failed-run evidence; KSP uses a fresh cache/output directory.
        Path previous = work.resolve("generated");
        if (Files.exists(previous)) {
            Files.move(previous, work.resolve("previous-generated-" + System.nanoTime()));
        }
    }

    private void javaSource(String name, String text) throws IOException {
        write(work.resolve("src/java").resolve(name), text);
    }

    private void kotlinSource(String name, String text) throws IOException {
        write(work.resolve("src/kotlin").resolve(name), text);
    }

    private static String activity(String name, String path, String extra) {
        return "package fixture; @com.alibaba.android.arouter.facade.annotation.Route(path=" + quote(path)
                + (extra.isEmpty() ? "" : ", " + extra) + ") public class " + name + " extends android.app.Activity {}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, String> options(String module) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("AROUTER_MODULE_NAME", module);
        return options;
    }

    private static List<Path> sources(Path directory, String suffix) throws IOException {
        if (!Files.exists(directory)) return new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(p -> p.toString().endsWith(suffix)).sorted().collect(Collectors.toList());
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) throw new IllegalStateException("Missing Gradle test property: " + name);
        return value;
    }

    private static void succeed(Result result) {
        assertEquals(result.toString(), 0, result.exitCode);
    }

    private static void fails(Result result, String diagnostic) {
        assertNotEquals(result.toString(), 0, result.exitCode);
        assertTrue(result.toString(), result.output.toLowerCase(Locale.ROOT).contains(diagnostic.toLowerCase(Locale.ROOT)));
    }

    private static final class Result {
        final int exitCode;
        final String output;
        final Path log;

        Result(int exitCode, String output, Path log) {
            this.exitCode = exitCode;
            this.output = output;
            this.log = log;
        }

        @Override public String toString() {
            return "exit=" + exitCode + ", log=" + log + "\n" + output;
        }
    }
}

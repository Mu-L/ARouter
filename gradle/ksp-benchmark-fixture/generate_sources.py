#!/usr/bin/env python3
"""Generate identical, legacy-compatible application inputs for both benchmark arms."""

import argparse
import hashlib
import json
from pathlib import Path
import textwrap


PACKAGE = "com.alibaba.android.arouter.kspbenchmark"
PACKAGE_PATH = PACKAGE.replace(".", "/")
JAVA_ROOT = f"app/src/main/java/{PACKAGE_PATH}"
KOTLIN_ROOT = f"app/src/main/kotlin/{PACKAGE_PATH}"


def source(text):
    return textwrap.dedent(text).strip() + "\n"


def write_utf8(path, content):
    with path.open("w", encoding="utf-8", newline="\n") as output:
        output.write(content)


def java_route(index):
    extra_field = ""
    lifecycle = ""
    if index == 0:
        extra_field = "    @Autowired(required = true) public ServiceApi service;\n"
        lifecycle = source("""
            @Override
            protected void onCreate(Bundle state) {
                super.onCreate(state);
                ARouter.getInstance().inject(this);
                SmokeState.check(number == 42 && "probe".equals(label), "Java parameter injection");
                SmokeState.check(service != null && "ready".equals(service.message()), "Java provider injection");
                SmokeState.check("intercepted".equals(getIntent().getStringExtra("trace")), "Java interception");
                SmokeState.check(SmokeState.interceptions.get() == 1, "First navigation interceptor count");
                SmokeState.javaArrived = true;
                ARouter.getInstance().build("/kotlin/route0").withInt("number", 84)
                        .withString("label", "java").navigation(this);
                finish();
            }
        """)
    return (
        f"package {PACKAGE}.routes;\n\n"
        "import android.app.Activity;\n"
        "import android.os.Bundle;\n"
        "import com.alibaba.android.arouter.facade.annotation.Autowired;\n"
        "import com.alibaba.android.arouter.facade.annotation.Route;\n"
        "import com.alibaba.android.arouter.launcher.ARouter;\n"
        f"import {PACKAGE}.ServiceApi;\n"
        f"import {PACKAGE}.SmokeState;\n\n"
        f'@Route(path = "/java/route{index}")\n'
        f"public final class Java{index} extends Activity {{\n"
        "    @Autowired public int number = 7;\n"
        '    @Autowired public String label = "default";\n'
        + extra_field
        + "    public int benchmarkBody() { return 0; }\n"
        + (textwrap.indent(lifecycle, "    ") if lifecycle else "")
        + "}\n"
    )


def kotlin_route(index):
    extra_field = ""
    lifecycle = ""
    route_marker = " // BENCHMARK_ROUTE" if index == 0 else ""
    body_marker = " // BENCHMARK_BODY" if index == 0 else ""
    if index == 0:
        extra_field = "    @Autowired(required = true) @JvmField var service: ServiceApi? = null\n"
        lifecycle = source("""
            override fun onCreate(state: Bundle?) {
                super.onCreate(state)
                ARouter.getInstance().inject(this)
                SmokeState.check(number == 84 && label == "java", "Kotlin parameter injection")
                SmokeState.check(service?.message() == "ready", "Kotlin provider injection")
                SmokeState.check(intent.getStringExtra("trace") == "intercepted", "Kotlin interception")
                SmokeState.check(SmokeState.javaArrived, "Java navigation must precede Kotlin")
                SmokeState.check(SmokeState.interceptions.get() == 2, "Exactly two route interceptions")
                SmokeState.completed = true
            }
        """)
    return (
        f"package {PACKAGE}.routes\n\n"
        "import android.app.Activity\n"
        "import android.os.Bundle\n"
        "import com.alibaba.android.arouter.facade.annotation.Autowired\n"
        "import com.alibaba.android.arouter.facade.annotation.Route\n"
        "import com.alibaba.android.arouter.launcher.ARouter\n"
        f"import {PACKAGE}.ServiceApi\n"
        f"import {PACKAGE}.SmokeState\n\n"
        f'@Route(path = "/kotlin/route{index}"){route_marker}\n'
        f"class Kotlin{index} : Activity() {{\n"
        "    @Autowired @JvmField var number: Int = 7\n"
        '    @Autowired @JvmField var label: String? = "default"\n'
        + extra_field
        + f"    fun benchmarkBody(): Int = 0{body_marker}\n"
        + (textwrap.indent(lifecycle, "    ") if lifecycle else "")
        + "}\n"
    )


def common_sources():
    files = {}
    files[f"{JAVA_ROOT}/ServiceApi.java"] = source("""
        package __PACKAGE__;
        public interface ServiceApi extends com.alibaba.android.arouter.facade.template.IProvider {
            String message();
        }
    """)
    files[f"{JAVA_ROOT}/BenchmarkService.java"] = source("""
        package __PACKAGE__;
        @com.alibaba.android.arouter.facade.annotation.Route(path = "/shared/service")
        public final class BenchmarkService implements ServiceApi {
            private boolean initialized;
            @Override public void init(android.content.Context context) { initialized = true; }
            @Override public String message() { return initialized ? "ready" : "uninitialized"; }
        }
    """)
    files[f"{JAVA_ROOT}/BenchmarkInterceptor.java"] = source("""
        package __PACKAGE__;
        import android.content.Context;
        import com.alibaba.android.arouter.facade.Postcard;
        import com.alibaba.android.arouter.facade.annotation.Interceptor;
        import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
        import com.alibaba.android.arouter.facade.template.IInterceptor;
        @Interceptor(priority = 7)
        public final class BenchmarkInterceptor implements IInterceptor {
            private boolean initialized;
            @Override public void init(Context context) { initialized = true; }
            @Override public void process(Postcard postcard, InterceptorCallback callback) {
                SmokeState.check(initialized, "Interceptor must be initialized");
                if (postcard.getPath().startsWith("/java/") || postcard.getPath().startsWith("/kotlin/")) {
                    SmokeState.interceptions.incrementAndGet();
                    postcard.withString("trace", "intercepted");
                }
                callback.onContinue(postcard);
            }
        }
    """)
    files[f"{JAVA_ROOT}/SmokeState.java"] = source("""
        package __PACKAGE__;
        import java.util.concurrent.atomic.AtomicInteger;
        public final class SmokeState {
            public static final AtomicInteger interceptions = new AtomicInteger();
            public static volatile boolean javaArrived;
            public static volatile boolean completed;
            private SmokeState() {}
            public static void reset() {
                interceptions.set(0);
                javaArrived = false;
                completed = false;
            }
            public static void check(boolean condition, String message) {
                if (!condition) throw new IllegalStateException(message);
            }
        }
    """)
    files[f"{JAVA_ROOT}/SmokeProbe.java"] = source("""
        package __PACKAGE__;
        import android.app.Activity;
        import android.os.Bundle;
        import com.alibaba.android.arouter.launcher.ARouter;
        public final class SmokeProbe extends Activity {
            @Override protected void onCreate(Bundle state) {
                super.onCreate(state);
                SmokeState.reset();
                ARouter.init(getApplication());
                ServiceApi service = ARouter.getInstance().navigation(ServiceApi.class);
                SmokeState.check(service != null && "ready".equals(service.message()), "Provider type lookup");
                SmokeState.check(ARouter.getInstance().build("/shared/service").navigation() == service,
                        "Provider path lookup must reuse the initialized instance");
                ARouter.getInstance().build("/java/route0").withInt("number", 42)
                        .withString("label", "probe").navigation(this);
            }
        }
    """)
    files[f"app/src/androidTest/java/{PACKAGE_PATH}/BenchmarkSmokeTest.java"] = source("""
        package __PACKAGE__;
        import android.app.Activity;
        import android.app.Instrumentation;
        import android.content.Intent;
        import androidx.test.platform.app.InstrumentationRegistry;
        import org.junit.Test;
        import static org.junit.Assert.assertNotNull;
        import static org.junit.Assert.assertTrue;
        public final class BenchmarkSmokeTest {
            @Test public void verifiesIdenticalJavaKotlinRoutingAndInjection() {
                Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
                Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                        "__PACKAGE__.routes.Kotlin0", null, false);
                Activity probe = null;
                Activity destination = null;
                try {
                    Intent intent = new Intent();
                    intent.setClassName(instrumentation.getTargetContext(), "__PACKAGE__.SmokeProbe");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    probe = instrumentation.startActivitySync(intent);
                    destination = monitor.waitForActivityWithTimeout(15000);
                    assertNotNull("Java0 to Kotlin0 navigation did not complete", destination);
                    instrumentation.waitForIdleSync();
                    assertTrue("Routing, injection, provider and interception assertions must complete",
                            SmokeState.completed);
                } finally {
                    finish(instrumentation, destination);
                    finish(instrumentation, probe);
                    instrumentation.removeMonitor(monitor);
                }
            }
            private static void finish(Instrumentation instrumentation, final Activity activity) {
                if (activity != null) {
                    instrumentation.runOnMainSync(new Runnable() {
                        @Override public void run() { activity.finish(); }
                    });
                    instrumentation.waitForIdleSync();
                }
            }
        }
    """)
    return {path: content.replace("__PACKAGE__", PACKAGE) for path, content in files.items()}


def generate(output, routes):
    files = common_sources()
    count = routes // 2
    activities = []
    for index in range(count):
        files[f"{JAVA_ROOT}/routes/Java{index}.java"] = java_route(index)
        files[f"{KOTLIN_ROOT}/routes/Kotlin{index}.kt"] = kotlin_route(index)
        activities.append(f'        <activity android:name=".routes.Java{index}" android:exported="false" />')
        activities.append(f'        <activity android:name=".routes.Kotlin{index}" android:exported="false" />')
    files["app/src/main/AndroidManifest.xml"] = (
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <application android:label="ARouter processor benchmark"\n'
        '            android:theme="@android:style/Theme.Material.Light.NoActionBar">\n'
        '        <activity android:name=".SmokeProbe" android:exported="true" />\n'
        + "\n".join(activities)
        + "\n    </application>\n</manifest>\n"
    )
    mutation_path = f"{KOTLIN_ROOT}/routes/Kotlin0.kt"
    mutations = {
        "body": {
            "path": mutation_path,
            "initial": "fun benchmarkBody(): Int = 0 // BENCHMARK_BODY",
            "alternate": "fun benchmarkBody(): Int = 1 // BENCHMARK_BODY",
        },
        "route": {
            "path": mutation_path,
            "initial": '@Route(path = "/kotlin/route0") // BENCHMARK_ROUTE',
            "alternate": '@Route(path = "/kotlin/changed0") // BENCHMARK_ROUTE',
        },
        "addition": {
            "path": f"{JAVA_ROOT}/routes/AddedFragment.java",
            "content": source(f"""
                package {PACKAGE}.routes;
                @com.alibaba.android.arouter.facade.annotation.Route(path = "/added/fragment")
                public final class AddedFragment extends android.app.Fragment {{}}
            """),
        },
    }
    for mutation in ("body", "route"):
        data = mutations[mutation]
        if files[data["path"]].count(data["initial"]) != 1 or data["alternate"] in files[data["path"]]:
            raise ValueError(f"Mutation {mutation} must have exactly one initial literal and no alternate")
    source_directory = output / "app/src"
    if source_directory.exists():
        unexpected = [
            path.relative_to(output).as_posix()
            for path in source_directory.rglob("*")
            if path.is_file() and path.relative_to(output).as_posix() not in files
        ]
        if unexpected:
            raise ValueError(f"Use a fresh fixture output; unexpected source files: {unexpected}")
    for relative, content in sorted(files.items()):
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        write_utf8(path, content)
    hashes = {
        path: hashlib.sha256(content.encode("utf-8")).hexdigest()
        for path, content in sorted(files.items())
    }
    manifest = {
        "schemaVersion": 1,
        "routes": routes,
        "javaRoutes": count,
        "kotlinRoutes": count,
        "sharedProviderRoutes": 1,
        "applicationId": PACKAGE,
        "instrumentationRunner": "androidx.test.runner.AndroidJUnitRunner",
        "smokeTest": f"{PACKAGE}.BenchmarkSmokeTest",
        "sourceHashes": {path: value for path, value in hashes.items() if path.startswith("app/src/main/")},
        "instrumentationHashes": {
            path: value for path, value in hashes.items() if path.startswith("app/src/androidTest/")
        },
        "mutations": mutations,
    }
    output.mkdir(parents=True, exist_ok=True)
    write_utf8(output / "benchmark-inputs.json", json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="Root of a private copied fixture project")
    parser.add_argument("--routes", type=int, default=128, help="Activity targets, evenly split between Java and Kotlin")
    args = parser.parse_args()
    if args.routes < 2 or args.routes % 2:
        parser.error("--routes must be a positive even number of at least 2")
    manifest = generate(args.output.resolve(), args.routes)
    print(json.dumps({"output": str(args.output.resolve()), "routes": manifest["routes"]}))


if __name__ == "__main__":
    main()

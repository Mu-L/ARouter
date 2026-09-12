package com.alibaba.android.arouter.compiler.ksp;

import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** A second processor that makes route discovery and type resolution span rounds. */
public final class RoundGeneratingProvider implements SymbolProcessorProvider {
    @Override
    public SymbolProcessor create(final SymbolProcessorEnvironment environment) {
        return new SymbolProcessor() {
            private boolean generated;

            @Override
            public List<KSAnnotated> process(Resolver resolver) {
                if (generated) {
                    return Collections.emptyList();
                }
                generated = true;
                write("GeneratedBase", "public class GeneratedBase extends android.app.Activity {}");
                String path = "duplicate".equals(environment.getOptions().get("probe.generate"))
                        ? "/round/initial" : "/round/generated";
                write("GeneratedActivity",
                        "@com.alibaba.android.arouter.facade.annotation.Route(path = \"" + path + "\") "
                        + "public class GeneratedActivity extends GeneratedBase {}");
                if ("features".equals(environment.getOptions().get("probe.generate"))) {
                    write("GeneratedPayload", "public class GeneratedPayload {}");
                    write("GeneratedInjected", "public class GeneratedInjected extends android.app.Activity {"
                            + "@com.alibaba.android.arouter.facade.annotation.Autowired public int count=3; }");
                    write("GeneratedInterceptor",
                            "@com.alibaba.android.arouter.facade.annotation.Interceptor(priority=31)"
                            + "public class GeneratedInterceptor implements com.alibaba.android.arouter.facade.template.IInterceptor {"
                            + "public void init(android.content.Context c) {}"
                            + "public void process(com.alibaba.android.arouter.facade.Postcard p,"
                            + "com.alibaba.android.arouter.facade.callback.InterceptorCallback c) {c.onContinue(p);} }");
                }
                return Collections.emptyList();
            }

            private void write(String name, String body) {
                try (OutputStream stream = environment.getCodeGenerator().createNewFile(
                        new Dependencies(true), "late", name, "java")) {
                    stream.write(("package late;\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public void finish() {
            }

            @Override
            public void onError() {
            }
        };
    }
}

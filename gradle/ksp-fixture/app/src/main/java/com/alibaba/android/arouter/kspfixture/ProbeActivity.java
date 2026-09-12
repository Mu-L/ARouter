package com.alibaba.android.arouter.kspfixture;

import android.app.Activity;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.alibaba.android.arouter.kspfixture.legacy.LegacyService;
import com.alibaba.android.arouter.launcher.ARouter;
import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.callback.NavigationCallback;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ARouter.init(getApplication());

        // Both route tables are discovered by the existing register plugin:
        // this module uses KSP, while the dependency uses Java/Kotlin KAPT.
        LegacyService legacy = ARouter.getInstance().navigation(LegacyService.class);
        KspService modern = ARouter.getInstance().navigation(KspService.class);
        if (legacy == null || !"kapt".equals(legacy.backend())
                || modern == null || !"ksp".equals(modern.backend())) {
            throw new IllegalStateException("Cannot resolve providers from both processor backends");
        }
        if (ARouter.getInstance().build("/legacy/provider").navigation() != legacy
                || ARouter.getInstance().build("/ksp/provider").navigation() != modern) {
            throw new IllegalStateException("Provider path lookup does not reuse initialized services");
        }

        assertFragment("/ksp/java-fragment", JavaFragment.class);
        assertFragment("/ksp/kotlin-fragment", KotlinFragment.class);
        InjectionChecks.run();

        // Start the successful route only after the earlier navigation has
        // actually been cancelled. No blocking wait runs on the main thread.
        final AtomicInteger interrupts = new AtomicInteger();
        ARouter.getInstance().build("/ksp/java").withBoolean("cancel", true)
                .withString("source", "cancelled").navigation(this, new NavigationCallback() {
                    @Override public void onFound(Postcard postcard) {}
                    @Override public void onLost(Postcard postcard) {
                        throw new IllegalStateException("Cancellation fixture route was not found");
                    }
                    @Override public void onArrival(Postcard postcard) {
                        throw new IllegalStateException("Cancelled navigation arrived");
                    }
                    @Override public void onInterrupt(Postcard postcard) {
                        InjectionChecks.check(interrupts.incrementAndGet() == 1, "Cancellation callback count");
                        InjectionChecks.checkTrace(postcard.getExtras().getString("trace"));
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                ARouter.getInstance().build("/ksp/java")
                                        .withString("source", "probe").navigation(ProbeActivity.this);
                            }
                        });
                    }
                });
    }

    private void assertFragment(String path, Class<? extends Fragment> expected) {
        Object destination = ARouter.getInstance().build(path)
                .withString("source", "ksp-fixture").navigation();
        if (!expected.isInstance(destination)) {
            throw new IllegalStateException("Cannot route Fragment " + path);
        }
        Bundle arguments = ((Fragment) destination).getArguments();
        if (arguments == null || !"ksp-fixture".equals(arguments.getString("source"))) {
            throw new IllegalStateException("Cannot pass Fragment arguments " + path);
        }
    }
}

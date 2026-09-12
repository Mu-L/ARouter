package com.alibaba.android.arouter.kspfixture;

import android.app.Activity;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.alibaba.android.arouter.kspfixture.legacy.LegacyService;
import com.alibaba.android.arouter.launcher.ARouter;

public final class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ARouter.init(getApplication());

        // Both route tables are discovered by the existing register plugin:
        // this module uses KSP, while the dependency uses Java APT.
        LegacyService legacy = ARouter.getInstance().navigation(LegacyService.class);
        KspService modern = ARouter.getInstance().navigation(KspService.class);
        if (legacy == null || !"apt".equals(legacy.backend())
                || modern == null || !"ksp".equals(modern.backend())) {
            throw new IllegalStateException("Cannot resolve providers from both processor backends");
        }
        if (ARouter.getInstance().build("/legacy/provider").navigation() != legacy
                || ARouter.getInstance().build("/ksp/provider").navigation() != modern) {
            throw new IllegalStateException("Provider path lookup does not reuse initialized services");
        }

        assertFragment("/ksp/java-fragment", JavaFragment.class);
        assertFragment("/ksp/kotlin-fragment", KotlinFragment.class);

        // The Java route then navigates to the Kotlin route. Instrumentation
        // observes the final component, so class retention is exercised by R8.
        ARouter.getInstance().build("/ksp/java").withString("source", "probe").navigation(this);
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

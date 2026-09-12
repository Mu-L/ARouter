package com.alibaba.android.arouter.kspfixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public final class KspConsumerInstrumentedTest {
    @Test
    public void routesJavaAndKotlinWithMixedCompilerModules() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        // Component names avoid test APK references that could require extra
        // keep rules. The target uses only the published consumer R8 rules.
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                "com.alibaba.android.arouter.kspfixture.KotlinActivity", null, false);
        Activity probe = null;
        Activity destination = null;
        try {
            Intent intent = new Intent();
            intent.setClassName(instrumentation.getTargetContext(),
                    "com.alibaba.android.arouter.kspfixture.ProbeActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            probe = instrumentation.startActivitySync(intent);
            destination = monitor.waitForActivityWithTimeout(10000);
            assertNotNull("KSP consumer navigation did not arrive", destination);
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

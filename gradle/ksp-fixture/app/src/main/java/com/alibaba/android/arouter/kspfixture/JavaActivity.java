package com.alibaba.android.arouter.kspfixture;

import android.app.Activity;
import android.os.Bundle;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.launcher.ARouter;

@Route(path = "/ksp/java")
public final class JavaActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!"probe".equals(getIntent().getStringExtra("source"))) {
            throw new IllegalStateException("Java Activity route lost its extras");
        }
        ARouter.getInstance().build("/ksp/kotlin").withString("source", "java").navigation(this);
        finish();
    }
}

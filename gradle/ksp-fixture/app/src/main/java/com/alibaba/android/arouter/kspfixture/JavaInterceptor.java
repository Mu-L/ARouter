package com.alibaba.android.arouter.kspfixture;

import android.content.Context;
import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.annotation.Interceptor;
import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
import com.alibaba.android.arouter.facade.template.IInterceptor;

@Interceptor(priority = -10)
public final class JavaInterceptor implements IInterceptor {
    private boolean initialized;
    @Override public void init(Context context) { initialized = true; }
    @Override public void process(Postcard postcard, InterceptorCallback callback) {
        InjectionChecks.check(initialized, "Java interceptor initialization");
        InjectionChecks.check(postcard.getExtras().getString("trace") == null, "Java interceptor must run first");
        postcard.withString("trace", "java");
        callback.onContinue(postcard);
    }
}

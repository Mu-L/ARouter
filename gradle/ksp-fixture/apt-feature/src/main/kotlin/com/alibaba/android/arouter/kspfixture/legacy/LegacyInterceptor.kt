package com.alibaba.android.arouter.kspfixture.legacy

import android.content.Context
import com.alibaba.android.arouter.facade.Postcard
import com.alibaba.android.arouter.facade.annotation.Interceptor
import com.alibaba.android.arouter.facade.callback.InterceptorCallback
import com.alibaba.android.arouter.facade.template.IInterceptor

// This Kotlin class is processed by KAPT, while the application uses KSP.
@Interceptor(priority = 0)
class LegacyInterceptor : IInterceptor {
    private var initialized = false
    override fun init(context: Context) { initialized = true }
    override fun process(postcard: Postcard, callback: InterceptorCallback) {
        check(initialized && postcard.extras.getString("trace") == "java") {
            "KAPT interceptor initialization/order"
        }
        postcard.withString("trace", "java,kapt")
        callback.onContinue(postcard)
    }
}

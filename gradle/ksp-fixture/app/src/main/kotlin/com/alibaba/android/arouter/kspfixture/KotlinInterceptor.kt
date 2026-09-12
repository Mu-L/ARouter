package com.alibaba.android.arouter.kspfixture

import android.content.Context
import com.alibaba.android.arouter.facade.Postcard
import com.alibaba.android.arouter.facade.annotation.Interceptor
import com.alibaba.android.arouter.facade.callback.InterceptorCallback
import com.alibaba.android.arouter.facade.template.IInterceptor

@Interceptor(priority = 10)
class KotlinInterceptor(private val marker: String = "kotlin") : IInterceptor {
    private var initialized = false
    override fun init(context: Context) { initialized = true }
    override fun process(postcard: Postcard, callback: InterceptorCallback) {
        check(initialized) { "Kotlin interceptor initialization" }
        check(postcard.extras.getString("trace") == "java,kapt") { "Mixed interceptor priority order" }
        postcard.withString("trace", "java,kapt,$marker")
        if (postcard.extras.getBoolean("cancel")) {
            callback.onInterrupt(IllegalStateException("fixture cancellation"))
        } else {
            callback.onContinue(postcard)
        }
    }
}

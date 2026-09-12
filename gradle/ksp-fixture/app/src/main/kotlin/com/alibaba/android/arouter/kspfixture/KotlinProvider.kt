package com.alibaba.android.arouter.kspfixture

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route

@Route(path = "/ksp/provider")
class KotlinProvider : KspService {
    private var initialized = false

    override fun init(context: Context) {
        initialized = true
    }

    override fun backend(): String {
        check(initialized) { "KSP provider was not initialized" }
        return "ksp"
    }
}

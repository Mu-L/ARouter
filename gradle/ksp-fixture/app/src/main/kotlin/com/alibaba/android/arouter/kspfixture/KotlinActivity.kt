package com.alibaba.android.arouter.kspfixture

import android.app.Activity
import android.os.Bundle
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.launcher.ARouter

@Route(path = "/ksp/kotlin")
class KotlinActivity : Activity() {
    @Autowired lateinit var source: String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ARouter.getInstance().inject(this)
        check(source == "java") {
            "Kotlin Activity route lost its extras"
        }
        InjectionChecks.checkTrace(intent.getStringExtra("trace"))
    }
}

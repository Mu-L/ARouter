package com.alibaba.android.arouter.kspfixture

import android.app.Activity
import android.os.Bundle
import com.alibaba.android.arouter.facade.annotation.Route

@Route(path = "/ksp/kotlin")
class KotlinActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(intent.getStringExtra("source") == "java") {
            "Kotlin Activity route lost its extras"
        }
    }
}

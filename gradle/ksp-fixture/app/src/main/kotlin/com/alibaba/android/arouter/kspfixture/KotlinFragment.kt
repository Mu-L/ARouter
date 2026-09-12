package com.alibaba.android.arouter.kspfixture

import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route

@Route(path = "/ksp/kotlin-fragment")
class KotlinFragment : JavaFragment() {
    @Autowired @JvmField var kotlinCount: Int = 11
    @Autowired @JvmField var kotlinBoxed: Int? = 13
    @Autowired @JvmField var kotlinText: String? = "kotlin-default"
    @Autowired @JvmField var kotlinPayloads: List<Payload> = listOf(Payload("kotlin-default"))
    @Autowired lateinit var lateText: String

    fun isLateTextInitialized(): Boolean = ::lateText.isInitialized
}

package com.chagok.app.ux

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * AAOS CarUxRestrictions 수신. 주행 규제 상태(requiresDistractionOptimization)이면 입력을 막습니다.
 * android.car 라이브러리 없이 빌드되도록 리플렉션으로 연결하며, AAOS가 아닌 환경에서는 조용히 비활성화됩니다.
 * (앱 자체 판정인 '주행 중' 상태에서도 입력을 막으므로 이중으로 보호)
 */
class UxRestrictionWatcher(private val context: Context, private val onChange: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Any? = null
    private var manager: Any? = null
    private var listenerProxy: Any? = null

    fun start(): Boolean = try {
        val carClass = Class.forName("android.car.Car")
        val created = carClass.getMethod("createCar", Context::class.java).invoke(null, context.applicationContext)
        car = created
        val serviceName = carClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
        val mgr = carClass.getMethod("getCarManager", String::class.java).invoke(created, serviceName)
        manager = mgr
        val listenerType = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
        val handler = InvocationHandler { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ChagokUxListener"
                "onUxRestrictionsChanged" -> {
                    report(args?.firstOrNull())
                    null
                }
                else -> null
            }
        }
        val proxy = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType), handler)
        listenerProxy = proxy
        mgr!!.javaClass.getMethod("registerListener", listenerType).invoke(mgr, proxy)
        report(mgr.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(mgr))
        true
    } catch (t: Throwable) {
        false
    }

    fun stop() {
        runCatching { manager?.javaClass?.getMethod("unregisterListener")?.invoke(manager) }
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        manager = null
        car = null
        listenerProxy = null
    }

    private fun report(restrictions: Any?) {
        val required = runCatching {
            restrictions!!.javaClass.getMethod("isRequiresDistractionOptimization").invoke(restrictions) as Boolean
        }.getOrDefault(false)
        main.post { onChange(required) }
    }
}

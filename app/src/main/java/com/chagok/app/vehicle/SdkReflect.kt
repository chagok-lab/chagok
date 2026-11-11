package com.chagok.app.vehicle

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Vehicle SDK 2.3.2 도메인 API 중 공개 문서에 타입 시그니처가 없는 메서드(Odometer, CarInfo, Fuel)를
 * 공식 샘플 앱(VehicleApiExplorer)과 같은 방식으로 호출합니다.
 * - get 계열: 함수형 콜백 파라미터(onComplete / onFailed)에 람다를 넣어 호출
 * - register 계열: ai.pleos.playground.vehicle.listener 패키지의 listener 인터페이스를 동적 프록시로 구현
 * 콜백 인자가 Throwable 이면 실패, 그 밖에는 값으로 처리하므로 파라미터 순서가 달라도 동작합니다.
 */
object SdkReflect {

    private fun isFunction(type: Class<*>) = type.name.startsWith("kotlin.jvm.functions.Function")

    fun findGetter(target: Any, name: String): Method? =
        target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.isNotEmpty() && m.parameterTypes.all { isFunction(it) }
        }

    /** 이름 조건에 맞는 첫 번째 get 메서드 이름 (예: Fuel 의 연비 API 탐색) */
    fun findGetterName(target: Any, predicate: (String) -> Boolean): String? =
        target.javaClass.methods
            .filter { m -> m.name.startsWith("get") && m.parameterTypes.isNotEmpty() && m.parameterTypes.all { isFunction(it) } }
            .map { it.name }
            .firstOrNull(predicate)

    fun callGet(target: Any, name: String, onValue: (Any?) -> Unit, onError: (Throwable) -> Unit): Boolean {
        val method = findGetter(target, name) ?: return false
        val dispatch: (List<Any?>) -> Unit = { values ->
            val error = values.firstOrNull { it is Throwable } as? Throwable
            if (error != null) onError(error) else onValue(values.lastOrNull { it != null } ?: values.firstOrNull())
        }
        val args = arrayOfNulls<Any>(method.parameterTypes.size)
        method.parameterTypes.forEachIndexed { i, type ->
            val fn0: () -> Unit = { dispatch(emptyList()) }
            val fn1: (Any?) -> Unit = { a -> dispatch(listOf(a)) }
            val fn2: (Any?, Any?) -> Unit = { a, b -> dispatch(listOf(a, b)) }
            val fn3: (Any?, Any?, Any?) -> Unit = { a, b, c -> dispatch(listOf(a, b, c)) }
            args[i] = when (type.name) {
                "kotlin.jvm.functions.Function0" -> fn0
                "kotlin.jvm.functions.Function1" -> fn1
                "kotlin.jvm.functions.Function2" -> fn2
                "kotlin.jvm.functions.Function3" -> fn3
                else -> null
            }
        }
        return try {
            method.invoke(target, *args)
            true
        } catch (t: Throwable) {
            onError(t.cause ?: t)
            false
        }
    }

    /** register 메서드에 listener 프록시를 등록하고, 해제용으로 프록시를 돌려줍니다. */
    fun register(target: Any, name: String, onValue: (Any?) -> Unit, onError: (Throwable) -> Unit): Any? {
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == 1 && m.parameterTypes[0].isInterface
        } ?: return null
        val listenerType = method.parameterTypes[0]
        val handler = InvocationHandler { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ChagokListener(${listenerType.simpleName})"
                else -> {
                    val first = args?.firstOrNull()
                    if (first is Throwable || m.name.contains("fail", ignoreCase = true)) {
                        onError(first as? Throwable ?: IllegalStateException(m.name))
                    } else {
                        onValue(first)
                    }
                    null
                }
            }
        }
        val proxy = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType), handler)
        return try {
            method.invoke(target, proxy)
            proxy
        } catch (t: Throwable) {
            onError(t.cause ?: t)
            null
        }
    }

    fun unregister(target: Any, name: String, proxy: Any?) {
        if (proxy == null) return
        val method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == 1 && m.parameterTypes[0].isInstance(proxy)
        } ?: return
        runCatching { method.invoke(target, proxy) }
    }

    /** 숫자, 또는 숫자 getter 를 가진 모델 객체에서 값을 꺼냅니다. */
    fun number(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        else -> value.javaClass.methods
            .firstOrNull { m ->
                m.parameterTypes.isEmpty() && m.name.startsWith("get") && m.name != "getClass" &&
                    (m.returnType.isPrimitive && m.returnType != java.lang.Boolean.TYPE && m.returnType != java.lang.Void.TYPE ||
                        Number::class.java.isAssignableFrom(m.returnType))
            }
            ?.let { m -> runCatching { (m.invoke(value) as? Number)?.toDouble() }.getOrNull() }
    }

    /** 모델 객체에서 후보 getter 순서대로 첫 번째 문자열 값을 꺼냅니다. */
    fun firstString(value: Any?, getters: List<String>): String? {
        if (value == null) return null
        for (g in getters) {
            val m = value.javaClass.methods.firstOrNull { it.name == g && it.parameterTypes.isEmpty() } ?: continue
            val v = runCatching { m.invoke(value) }.getOrNull()?.toString()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }
}

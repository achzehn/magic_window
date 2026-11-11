package com.github.lsposed.magicwindow.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 极简反射工具，替代经典 API 的 `XposedHelpers`。
 *
 * libxposed 不再提供 `XposedHelpers`，且下列目标类全部位于 system_server / HyperOS 插件内，
 * 成员多为包级私有，只能走反射 + `setAccessible(true)`。
 */
internal object Reflect {

    private data class MemberKey(val owner: Class<*>, val name: String, val count: Int = 0)

    /**
     * 反射结果缓存：method/field 每次沿继承链扫描 `declaredMethods` 的成本不低，
     * 而调用点（按规则数循环的注入）会重复命中同一批成员。
     */
    private val methodCache = java.util.concurrent.ConcurrentHashMap<MemberKey, Method>()
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<MemberKey, Field>()
    private val ctorCache = java.util.concurrent.ConcurrentHashMap<MemberKey, Constructor<*>>()

    fun findClass(name: String, classLoader: ClassLoader): Class<*> = Class.forName(name, false, classLoader)

    /** 按方法名 + 参数个数定位（参数精确类型在跨版本 ROM 上不稳定，按个数匹配更耐用） */
    fun method(target: Class<*>, name: String, paramCount: Int): Method {
        val key = MemberKey(target, name, paramCount)
        methodCache[key]?.let { return it }
        var c: Class<*>? = target
        while (c != null) {
            val found = c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }
            if (found != null) {
                found.isAccessible = true
                methodCache[key] = found
                return found
            }
            c = c.superclass
        }
        throw NoSuchMethodException("${target.name}#$name/$paramCount")
    }

    fun findMethods(target: Class<*>, name: String): List<Method> {
        var c: Class<*>? = target
        while (c != null) {
            val list = c.declaredMethods.filter { it.name == name }
            if (list.isNotEmpty()) return list.onEach { it.isAccessible = true }
            c = c.superclass
        }
        return emptyList()
    }

    fun constructor(target: Class<*>, paramCount: Int): Constructor<*> {
        val key = MemberKey(target, "<init>", paramCount)
        ctorCache[key]?.let { return it }
        val found = target.declaredConstructors.firstOrNull { it.parameterCount == paramCount }
            ?: throw NoSuchMethodException("${target.name}<init>/$paramCount")
        found.isAccessible = true
        ctorCache[key] = found
        return found
    }

    fun field(target: Class<*>, name: String): Field {
        val key = MemberKey(target, name)
        fieldCache[key]?.let { return it }
        var c: Class<*>? = target
        while (c != null) {
            val found = c.declaredFields.firstOrNull { it.name == name }
            if (found != null) {
                found.isAccessible = true
                fieldCache[key] = found
                return found
            }
            c = c.superclass
        }
        throw NoSuchFieldException("${target.name}#$name")
    }

    fun call(obj: Any, name: String, vararg args: Any?): Any? =
        method(obj.javaClass, name, args.size).invoke(obj, *args)

    fun callStatic(target: Class<*>, name: String, vararg args: Any?): Any? =
        method(target, name, args.size).invoke(null, *args)

    fun getField(obj: Any, name: String): Any? = field(obj.javaClass, name).get(obj)

    fun getBooleanField(obj: Any, name: String): Boolean = field(obj.javaClass, name).getBoolean(obj)

    fun setStaticBooleanField(target: Class<*>, name: String, value: Boolean) {
        field(target, name).setBoolean(null, value)
    }

    fun setStaticLongField(target: Class<*>, name: String, value: Long) {
        field(target, name).setLong(null, value)
    }
}

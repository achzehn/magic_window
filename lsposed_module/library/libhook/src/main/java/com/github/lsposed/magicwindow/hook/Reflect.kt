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

    fun findClass(name: String, classLoader: ClassLoader): Class<*> = Class.forName(name, false, classLoader)

    /** 按方法名 + 参数个数定位（参数精确类型在跨版本 ROM 上不稳定，按个数匹配更耐用） */
    fun method(target: Class<*>, name: String, paramCount: Int): Method {
        var c: Class<*>? = target
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }
                ?.let { return it.apply { isAccessible = true } }
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

    fun constructor(target: Class<*>, paramCount: Int): Constructor<*> =
        target.declaredConstructors.firstOrNull { it.parameterCount == paramCount }
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("${target.name}<init>/$paramCount")

    fun field(target: Class<*>, name: String): Field {
        var c: Class<*>? = target
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }
                ?.let { return it.apply { isAccessible = true } }
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

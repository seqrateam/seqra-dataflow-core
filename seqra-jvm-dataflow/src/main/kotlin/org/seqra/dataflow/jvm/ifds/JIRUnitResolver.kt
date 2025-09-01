@file:Suppress("FunctionName")

package org.seqra.dataflow.jvm.ifds

import org.seqra.ir.api.jvm.JIRClassOrInterface
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.ext.packageName
import org.seqra.dataflow.ifds.SingletonUnit
import org.seqra.dataflow.ifds.UnitResolver
import org.seqra.dataflow.ifds.UnitType

data class MethodUnit(val method: JIRMethod) : UnitType {
    override fun toString(): String {
        return "MethodUnit(${method.name})"
    }
}

data class ClassUnit(val clazz: JIRClassOrInterface) : UnitType {
    override fun toString(): String {
        return "ClassUnit(${clazz.simpleName})"
    }
}

data class PackageUnit(val packageName: String) : UnitType {
    override fun toString(): String {
        return "PackageUnit($packageName)"
    }
}

fun interface JIRUnitResolver : UnitResolver<JIRMethod>

val MethodUnitResolver = JIRUnitResolver { method ->
    MethodUnit(method)
}

private val ClassUnitResolverWithNested = JIRUnitResolver { method ->
    val clazz = generateSequence(method.enclosingClass) { it.outerClass }.last()
    ClassUnit(clazz)
}
private val ClassUnitResolverWithoutNested = JIRUnitResolver { method ->
    val clazz = method.enclosingClass
    ClassUnit(clazz)
}

fun ClassUnitResolver(includeNested: Boolean) =
    if (includeNested) {
        ClassUnitResolverWithNested
    } else {
        ClassUnitResolverWithoutNested
    }

val PackageUnitResolver = JIRUnitResolver { method ->
    PackageUnit(method.enclosingClass.packageName)
}

val SingletonUnitResolver = JIRUnitResolver {
    SingletonUnit
}

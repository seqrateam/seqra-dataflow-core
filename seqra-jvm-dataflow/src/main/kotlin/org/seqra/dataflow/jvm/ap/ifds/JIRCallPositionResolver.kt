package org.seqra.dataflow.jvm.ap.ifds

import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.JIRParameter
import org.seqra.ir.api.jvm.JIRType
import org.seqra.ir.api.jvm.cfg.JIRArgument
import org.seqra.ir.api.jvm.cfg.JIRCallExpr
import org.seqra.ir.api.jvm.cfg.JIRImmediate
import org.seqra.ir.api.jvm.cfg.JIRInstanceCallExpr
import org.seqra.ir.api.jvm.cfg.JIRValue
import org.seqra.ir.api.jvm.ext.toType
import org.seqra.dataflow.configuration.jvm.Argument
import org.seqra.dataflow.configuration.jvm.ClassStatic
import org.seqra.dataflow.configuration.jvm.Position
import org.seqra.dataflow.configuration.jvm.PositionResolver
import org.seqra.dataflow.configuration.jvm.PositionWithAccess
import org.seqra.dataflow.configuration.jvm.Result
import org.seqra.dataflow.configuration.jvm.This
import org.seqra.dataflow.jvm.util.thisInstance
import org.seqra.util.Maybe
import org.seqra.util.fmap
import org.seqra.util.toMaybe

class CallPositionToJIRValueResolver(
    private val callExpr: JIRCallExpr,
    private val returnValue: JIRImmediate?
) : PositionResolver<Maybe<JIRValue>> {
    override fun resolve(position: Position): Maybe<JIRValue> = when (position) {
        is Argument -> callExpr.args.getOrNull(position.index).toMaybe()
        This -> (callExpr as? JIRInstanceCallExpr)?.instance.toMaybe()
        Result -> returnValue.toMaybe()
        is PositionWithAccess -> Maybe.none() // todo?
        is ClassStatic -> Maybe.none()
    }
}

class CalleePositionToJIRValueResolver(
    private val method: JIRMethod
) : PositionResolver<Maybe<JIRValue>> {
    private val cp = method.enclosingClass.classpath

  override fun resolve(position: Position): Maybe<JIRValue> = when (position) {
        is Argument -> cp.getArgument(method.parameters[position.index]).toMaybe()
        This -> method.thisInstance.toMaybe()
        is PositionWithAccess -> resolve(position.base).fmap { TODO() }
        // Inapplicable callee positions
        Result -> Maybe.none()
        is ClassStatic -> Maybe.none()
    }

    private fun JIRClasspath.getArgument(param: JIRParameter): JIRArgument? {
        val t = findTypeOrNull(param.type.typeName) ?: return null
        return JIRArgument.of(param.index, param.name, t)
    }
}

class JIRMethodPositionBaseTypeResolver(private val method: JIRMethod) : PositionResolver<JIRType?> {
    private val cp = method.enclosingClass.classpath

    override fun resolve(position: Position): JIRType? = when (position) {
        This -> method.enclosingClass.toType()
        is Argument -> method.parameters.getOrNull(position.index)?.let { cp.findTypeOrNull(it.type.typeName) }
        Result -> cp.findTypeOrNull(method.returnType.typeName)
        is PositionWithAccess -> resolve(position.base)
        is ClassStatic -> null
    }
}

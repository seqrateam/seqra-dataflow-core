package org.seqra.dataflow.jvm.ap.ifds

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.common.cfg.CommonValue
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.cfg.JIRCallExpr
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.ir.api.jvm.cfg.JIRThrowInst
import org.seqra.ir.api.jvm.cfg.JIRValue
import org.seqra.ir.api.jvm.ext.cfg.callExpr
import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.LanguageManager
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

open class JIRLanguageManager(val cp: JIRClasspath) : LanguageManager {
    override fun getInstIndex(inst: CommonInst): Int {
        jIRDowncast<JIRInst>(inst)
        return inst.location.index
    }

    override fun getMaxInstIndex(method: CommonMethod): Int {
        jIRDowncast<JIRMethod>(method)
        return method.instList.maxOf { it.location.index }
    }

    override fun getInstByIndex(method: CommonMethod, index: Int): CommonInst {
        jIRDowncast<JIRMethod>(method)
        return method.instList[index]
    }

    override fun isEmpty(method: CommonMethod): Boolean {
        jIRDowncast<JIRMethod>(method)
        return method.instList.size == 0
    }

    override fun getCallExpr(inst: CommonInst): JIRCallExpr? {
        jIRDowncast<JIRInst>(inst)
        return inst.callExpr
    }

    override fun producesExceptionalControlFlow(inst: CommonInst): Boolean {
        return inst is JIRThrowInst
    }

    override fun getCalleeMethod(callExpr: CommonCallExpr): JIRMethod {
        jIRDowncast<JIRCallExpr>(callExpr)
        return callExpr.method.method
    }

    override val methodContextSerializer = JIRMethodContextSerializer(cp)
}

@OptIn(ExperimentalContracts::class)
internal inline fun <reified T> jIRDowncast(value: Any?) {
    contract {
        returns() implies(value is T)
    }
    check(value is T) { "Downcast error: expected ${T::class}, got $value" }
}
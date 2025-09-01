package org.seqra.dataflow.ap.ifds

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.dataflow.ap.ifds.serialization.MethodContextSerializer

interface LanguageManager {
    fun getInstIndex(inst: CommonInst): Int
    fun getMaxInstIndex(method: CommonMethod): Int
    fun getInstByIndex(method: CommonMethod, index: Int): CommonInst
    fun isEmpty(method: CommonMethod): Boolean
    fun getCallExpr(inst: CommonInst): CommonCallExpr?
    fun producesExceptionalControlFlow(inst: CommonInst): Boolean
    fun getCalleeMethod(callExpr: CommonCallExpr): CommonMethod

    val methodContextSerializer: MethodContextSerializer
}

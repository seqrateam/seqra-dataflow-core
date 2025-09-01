package org.seqra.dataflow.util

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonAssignInst
import org.seqra.ir.api.common.cfg.CommonExpr

interface SarifTraits<out Method, out Statement>
    where Method : CommonMethod,
          Statement : CommonInst {

    fun getCallee(callExpr: CommonCallExpr): Method
    fun getCalleeClassName(callExpr: CommonCallExpr): String
    fun getMethodClassName(md: @UnsafeVariance Method): String
    fun getCallExpr(statement: @UnsafeVariance Statement): CommonCallExpr?
    fun getAssign(statement: @UnsafeVariance Statement): CommonAssignInst?
    fun getReadableValue(statement: @UnsafeVariance Statement, expr: CommonExpr): String?
    fun getReadableAssignee(statement: @UnsafeVariance Statement): String?

    data class LocalInfo(val idx: Int, val name: String)
    fun getLocals(expr: CommonExpr): List<LocalInfo>
    fun isRegister(name: String): Boolean
    fun getLocalName(md: @UnsafeVariance Method, index: Int): String?
    fun printArgumentNth(index: Int): String
    fun printArgument(statement: @UnsafeVariance Statement, index: Int): String
    fun printThis(statement: @UnsafeVariance Statement): String

    fun lineNumber(statement: @UnsafeVariance Statement): Int
    fun locationFQN(statement: @UnsafeVariance Statement): String
    fun locationMachineName(statement: @UnsafeVariance Statement): String
}

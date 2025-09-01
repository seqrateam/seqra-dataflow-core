package org.seqra.dataflow.ap.ifds

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonInst

interface MethodContext

object EmptyMethodContext : MethodContext {
    override fun toString(): String = "{}"
}

data class MethodWithContext(val method: CommonMethod, val ctx: MethodContext)

data class MethodEntryPoint(val context: MethodContext, val statement: CommonInst) {
    val method: CommonMethod get() = statement.location.method

    override fun toString(): String = "$method [$context]"
}

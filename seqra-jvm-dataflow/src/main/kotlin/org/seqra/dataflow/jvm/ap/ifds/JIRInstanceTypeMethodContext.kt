package org.seqra.dataflow.jvm.ap.ifds

import org.seqra.ir.api.jvm.JIRClassOrInterface
import org.seqra.dataflow.ap.ifds.MethodContext

data class JIRInstanceTypeMethodContext(val type: JIRClassOrInterface) : MethodContext {
    override fun toString(): String = "{this is $type}"
}
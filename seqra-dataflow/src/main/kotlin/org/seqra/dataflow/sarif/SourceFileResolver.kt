package org.seqra.dataflow.sarif

import org.seqra.ir.api.common.cfg.CommonInst

fun interface SourceFileResolver<in Statement : CommonInst> {
    fun resolve(inst: Statement): String?
}

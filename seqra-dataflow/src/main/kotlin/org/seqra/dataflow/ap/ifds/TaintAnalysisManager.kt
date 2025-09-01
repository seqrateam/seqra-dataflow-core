package org.seqra.dataflow.ap.ifds

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.util.analysis.ApplicationGraph
import org.seqra.dataflow.ap.ifds.analysis.AnalysisManager
import org.seqra.dataflow.ap.ifds.analysis.MethodAnalysisContext
import org.seqra.dataflow.ap.ifds.taint.TaintAnalysisContext

interface TaintAnalysisManager : AnalysisManager {
    override fun getMethodAnalysisContext(
        methodEntryPoint: MethodEntryPoint,
        graph: ApplicationGraph<CommonMethod, CommonInst>
    ): MethodAnalysisContext {
        error("Taint context required")
    }

    fun getMethodAnalysisContext(
        methodEntryPoint: MethodEntryPoint,
        graph: ApplicationGraph<CommonMethod, CommonInst>,
        taintAnalysisContext: TaintAnalysisContext,
    ): MethodAnalysisContext
}

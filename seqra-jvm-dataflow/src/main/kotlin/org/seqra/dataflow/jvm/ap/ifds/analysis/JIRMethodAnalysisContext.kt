package org.seqra.dataflow.jvm.ap.ifds.analysis

import org.seqra.dataflow.ap.ifds.analysis.MethodAnalysisContext
import org.seqra.dataflow.ap.ifds.analysis.MethodCallFactMapper
import org.seqra.dataflow.ap.ifds.MethodEntryPoint
import org.seqra.dataflow.ap.ifds.taint.TaintAnalysisContext
import org.seqra.dataflow.jvm.ap.ifds.JIRFactTypeChecker
import org.seqra.dataflow.jvm.ap.ifds.JIRLocalAliasAnalysis
import org.seqra.dataflow.jvm.ap.ifds.JIRLocalVariableReachability
import org.seqra.dataflow.jvm.ap.ifds.JIRMethodCallFactMapper

class JIRMethodAnalysisContext(
    override val methodEntryPoint: MethodEntryPoint,
    val factTypeChecker: JIRFactTypeChecker,
    val localVariableReachability: JIRLocalVariableReachability,
    val aliasAnalysis: JIRLocalAliasAnalysis?,
    val taint: TaintAnalysisContext,
) : MethodAnalysisContext {
    override val methodCallFactMapper: MethodCallFactMapper
        get() = JIRMethodCallFactMapper
}

package org.seqra.dataflow.ap.ifds

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.util.analysis.ApplicationGraph
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.analysis.AnalysisManager
import org.seqra.dataflow.ap.ifds.analysis.MethodCallResolver

interface AnalysisRunner {
    val graph: ApplicationGraph<CommonMethod, CommonInst>
    val apManager: ApManager
    val analysisManager: AnalysisManager
    val manager: AnalysisUnitRunnerManager
    val methodCallResolver: MethodCallResolver

    fun enqueueMethodAnalyzer(analyzer: MethodAnalyzer)
    fun addNewSummaryEdges(methodEntryPoint: MethodEntryPoint, edges: List<Edge>)
    fun getPrecalculatedSummaries(methodEntryPoint: MethodEntryPoint): Pair<List<Edge>, List<InitialFactAp>>?
    fun addNewSideEffectRequirement(methodEntryPoint: MethodEntryPoint, requirements: List<InitialFactAp>)
    fun subscribeOnMethodSummaries(edge: Edge.ZeroToZero, methodEntryPoint: MethodEntryPoint)
    fun subscribeOnMethodSummaries(edge: Edge.ZeroToFact, methodEntryPoint: MethodEntryPoint, methodFactBase: AccessPathBase)
    fun subscribeOnMethodSummaries(edge: Edge.FactToFact, methodEntryPoint: MethodEntryPoint, methodFactBase: AccessPathBase)
    fun subscribeOnMethodSummaries(edge: Edge.NDFactToFact, methodEntryPoint: MethodEntryPoint, methodFactBase: AccessPathBase)
    fun submitExternalInitialZeroFact(methodEntryPoint: MethodEntryPoint)
    fun submitExternalInitialFact(methodEntryPoint: MethodEntryPoint, factAp: FinalFactAp)
}

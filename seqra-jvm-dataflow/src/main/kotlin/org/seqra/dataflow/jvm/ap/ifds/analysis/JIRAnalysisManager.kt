package org.seqra.dataflow.jvm.ap.ifds.analysis

import mu.KLogger
import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.MethodEntryPoint
import org.seqra.dataflow.ap.ifds.TaintAnalysisManager
import org.seqra.dataflow.ap.ifds.TaintAnalysisUnitRunner
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.analysis.MethodAnalysisContext
import org.seqra.dataflow.ap.ifds.analysis.MethodCallFlowFunction
import org.seqra.dataflow.ap.ifds.analysis.MethodCallSummaryHandler
import org.seqra.dataflow.ap.ifds.analysis.MethodSequentFlowFunction
import org.seqra.dataflow.ap.ifds.analysis.MethodStartFlowFunction
import org.seqra.dataflow.ap.ifds.taint.TaintAnalysisContext
import org.seqra.dataflow.ap.ifds.trace.MethodCallPrecondition
import org.seqra.dataflow.ap.ifds.trace.MethodSequentPrecondition
import org.seqra.dataflow.ap.ifds.trace.MethodStartPrecondition
import org.seqra.dataflow.ifds.UnitResolver
import org.seqra.dataflow.jvm.ap.ifds.JIRCallResolver
import org.seqra.dataflow.jvm.ap.ifds.JIRFactTypeChecker
import org.seqra.dataflow.jvm.ap.ifds.JIRLambdaTracker
import org.seqra.dataflow.jvm.ap.ifds.JIRLanguageManager
import org.seqra.dataflow.jvm.ap.ifds.JIRLocalAliasAnalysis
import org.seqra.dataflow.jvm.ap.ifds.JIRLocalVariableReachability
import org.seqra.dataflow.jvm.ap.ifds.JIRMethodCallFactMapper
import org.seqra.dataflow.jvm.ap.ifds.JIRMethodContextSerializer
import org.seqra.dataflow.jvm.ap.ifds.LambdaExpressionToAnonymousClassTransformerFeature
import org.seqra.dataflow.jvm.ap.ifds.jIRDowncast
import org.seqra.dataflow.jvm.ap.ifds.trace.JIRMethodCallPrecondition
import org.seqra.dataflow.jvm.ap.ifds.trace.JIRMethodSequentPrecondition
import org.seqra.dataflow.jvm.ap.ifds.trace.JIRMethodStartPrecondition
import org.seqra.dataflow.jvm.ifds.JIRUnitResolver
import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.common.cfg.CommonValue
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.cfg.JIRCallExpr
import org.seqra.ir.api.jvm.cfg.JIRImmediate
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.jvm.graph.JApplicationGraph
import org.seqra.util.analysis.ApplicationGraph

class JIRAnalysisManager(
    cp: JIRClasspath,
    private val applyAliasInfo: Boolean = false,
) : JIRLanguageManager(cp), TaintAnalysisManager {
    private val lambdaTracker = JIRLambdaTracker()
    private val factTypeChecker = JIRFactTypeChecker(cp)

    override fun getMethodCallResolver(
        graph: ApplicationGraph<CommonMethod, CommonInst>,
        unitResolver: UnitResolver<CommonMethod>,
        runner: TaintAnalysisUnitRunner
    ): JIRMethodCallResolver {
        jIRDowncast<JApplicationGraph>(graph)
        jIRDowncast<JIRUnitResolver>(unitResolver)

        val jIRCallResolver = JIRCallResolver(cp, graph, unitResolver)
        return JIRMethodCallResolver(lambdaTracker, jIRCallResolver, runner)
    }

    override fun getMethodAnalysisContext(
        methodEntryPoint: MethodEntryPoint,
        graph: ApplicationGraph<CommonMethod, CommonInst>,
        taintAnalysisContext: TaintAnalysisContext
    ): MethodAnalysisContext {
        val entryPointStatement = methodEntryPoint.statement
        jIRDowncast<JIRInst>(entryPointStatement)
        jIRDowncast<JApplicationGraph>(graph)

        val aliasAnalysis = if (applyAliasInfo) {
            JIRLocalAliasAnalysis(entryPointStatement, graph, this)
        } else {
            null
        }

        val method = entryPointStatement.location.method
        val localVariableReachability = JIRLocalVariableReachability(method, graph, this)
        return JIRMethodAnalysisContext(
            methodEntryPoint,
            factTypeChecker,
            localVariableReachability,
            aliasAnalysis,
            taintAnalysisContext
        )
    }

    override fun getMethodStartFlowFunction(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext
    ): MethodStartFlowFunction {
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)
        return JIRMethodStartFlowFunction(apManager, analysisContext)
    }

    override fun getMethodStartPrecondition(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext
    ): MethodStartPrecondition {
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)
        return JIRMethodStartPrecondition(apManager, analysisContext)
    }

    override fun getMethodSequentPrecondition(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext,
        currentInst: CommonInst
    ): MethodSequentPrecondition {
        jIRDowncast<JIRInst>(currentInst)
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)

        return JIRMethodSequentPrecondition(apManager, currentInst, analysisContext)
    }

    override fun getMethodSequentFlowFunction(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext,
        currentInst: CommonInst
    ): MethodSequentFlowFunction {
        jIRDowncast<JIRInst>(currentInst)
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)

        return JIRMethodSequentFlowFunction(apManager, analysisContext, currentInst)
    }

    override fun getMethodCallFlowFunction(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext,
        returnValue: CommonValue?,
        callExpr: CommonCallExpr,
        statement: CommonInst
    ): MethodCallFlowFunction {
        jIRDowncast<JIRImmediate?>(returnValue)
        jIRDowncast<JIRCallExpr>(callExpr)
        jIRDowncast<JIRInst>(statement)
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)

        return JIRMethodCallFlowFunction(
            apManager,
            analysisContext,
            returnValue,
            callExpr,
            statement,
        )
    }

    override fun getMethodCallSummaryHandler(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext,
        statement: CommonInst
    ): MethodCallSummaryHandler {
        jIRDowncast<JIRInst>(statement)
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)

        return JIRMethodCallSummaryHandler(statement, analysisContext)
    }

    override fun getMethodCallPrecondition(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext,
        returnValue: CommonValue?,
        callExpr: CommonCallExpr,
        statement: CommonInst
    ): MethodCallPrecondition {
        jIRDowncast<JIRImmediate?>(returnValue)
        jIRDowncast<JIRCallExpr>(callExpr)
        jIRDowncast<JIRInst>(statement)
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)

        return JIRMethodCallPrecondition(
            apManager,
            analysisContext,
            returnValue,
            callExpr,
            statement
        )
    }

    override fun isReachable(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext,
        base: AccessPathBase,
        statement: CommonInst
    ): Boolean {
        jIRDowncast<JIRMethodAnalysisContext>(analysisContext)
        return analysisContext.localVariableReachability.isReachable(base, statement)
    }

    override fun isValidMethodExitFact(
        apManager: ApManager,
        analysisContext: MethodAnalysisContext,
        fact: FinalFactAp
    ): Boolean {
        return JIRMethodCallFactMapper.isValidMethodExitFact(fact)
    }

    override val methodContextSerializer = JIRMethodContextSerializer(cp)

    override fun onInstructionReached(inst: CommonInst) {
        jIRDowncast<JIRInst>(inst)
        val allocatedLambda = LambdaExpressionToAnonymousClassTransformerFeature.findLambdaAllocation(inst)
        if (allocatedLambda != null) {
            lambdaTracker.registerLambda(allocatedLambda)
        }
    }

    override fun reportLanguageSpecificRunnerProgress(logger: KLogger) {
        logger.debug {
            val localTotal = factTypeChecker.localFactsTotal.sum()
            val localRejected = factTypeChecker.localFactsRejected.sum()
            val accessTotal = factTypeChecker.accessTotal.sum()
            val accessRejected = factTypeChecker.accessRejected.sum()
            buildString {
                append("Fact types: ")
                append("local $localRejected/$localTotal (${percentToString(localRejected, localTotal)})")
                append(" | ")
                append("access $accessRejected/$accessTotal (${percentToString(accessRejected, accessTotal)})")
            }
        }
    }

    private fun percentToString(current: Long, total: Long): String {
        val percentValue = current.toDouble() / total
        return String.format("%.2f", percentValue * 100) + "%"
    }
}
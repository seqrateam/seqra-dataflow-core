package org.seqra.dataflow.ap.ifds.analysis

import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.dataflow.ap.ifds.MethodAnalyzer
import org.seqra.dataflow.ap.ifds.MethodEntryPoint
import org.seqra.dataflow.ap.ifds.MethodWithContext

interface MethodCallResolver {
    fun resolveMethodCall(
        callerEntryPoint: MethodEntryPoint,
        callExpr: CommonCallExpr,
        location: CommonInst,
        handler: MethodAnalyzer.MethodCallHandler,
        failureHandler: MethodAnalyzer.MethodCallResolutionFailureHandler,
    )

    fun resolvedMethodCalls(
        callerEntryPoint: MethodEntryPoint,
        callExpr: CommonCallExpr,
        location: CommonInst
    ): List<MethodWithContext>
}
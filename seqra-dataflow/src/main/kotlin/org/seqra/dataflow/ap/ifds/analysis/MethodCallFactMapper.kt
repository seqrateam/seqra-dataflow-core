package org.seqra.dataflow.ap.ifds.analysis

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.common.cfg.CommonValue
import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.FactTypeChecker
import org.seqra.dataflow.ap.ifds.access.FactAp
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp

interface MethodCallFactMapper {
    fun mapMethodExitToReturnFlowFact(
        callStatement: CommonInst,
        factAp: FinalFactAp,
        checker: FactTypeChecker
    ): List<FinalFactAp>

    fun mapMethodExitToReturnFlowFact(
        callStatement: CommonInst,
        factAp: InitialFactAp
    ): List<InitialFactAp>

    fun mapMethodCallToStartFlowFact(
        callee: CommonMethod,
        callExpr: CommonCallExpr,
        factAp: FinalFactAp,
        checker: FactTypeChecker,
        onMappedFact: (FinalFactAp, AccessPathBase) -> Unit
    )

    fun mapMethodCallToStartFlowFact(
        callee: CommonMethod,
        callExpr: CommonCallExpr,
        fact: InitialFactAp,
        onMappedFact: (InitialFactAp, AccessPathBase) -> Unit
    )

    fun factIsRelevantToMethodCall(returnValue: CommonValue?, callExpr: CommonCallExpr, factAp: FactAp): Boolean
    fun isValidMethodExitFact(factAp: FactAp): Boolean
}
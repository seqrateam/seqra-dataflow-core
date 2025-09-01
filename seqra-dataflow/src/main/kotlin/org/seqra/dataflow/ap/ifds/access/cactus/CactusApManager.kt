package org.seqra.dataflow.ap.ifds.access.cactus

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.ExclusionSet.Empty
import org.seqra.dataflow.ap.ifds.FinalAccessor
import org.seqra.dataflow.ap.ifds.LanguageManager
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAbstraction
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.access.MethodAccessPathSubscription
import org.seqra.dataflow.ap.ifds.access.MethodEdgesFinalApSet
import org.seqra.dataflow.ap.ifds.access.MethodEdgesInitialToFinalApSet
import org.seqra.dataflow.ap.ifds.access.MethodEdgesNDInitialToFinalApSet
import org.seqra.dataflow.ap.ifds.access.MethodFinalApSummariesStorage
import org.seqra.dataflow.ap.ifds.access.MethodInitialToFinalApSummariesStorage
import org.seqra.dataflow.ap.ifds.access.MethodNDInitialToFinalApSummariesStorage
import org.seqra.dataflow.ap.ifds.access.SideEffectRequirementApStorage
import org.seqra.dataflow.ap.ifds.access.cactus.AccessCactus.AccessNode
import org.seqra.dataflow.ap.ifds.serialization.ApSerializer
import org.seqra.dataflow.ap.ifds.serialization.SummarySerializationContext
import org.seqra.ir.api.common.cfg.CommonInst

object CactusApManager : ApManager {
    override fun initialFactAbstraction(methodInitialStatement: CommonInst): InitialFactAbstraction =
        CactusInitialFactAbstraction()

    override fun methodEdgesFinalApSet(
        methodInitialStatement: CommonInst,
        maxInstIdx: Int,
        languageManager: LanguageManager
    ): MethodEdgesFinalApSet =
        MethodEdgesFinalCactusApSet(methodInitialStatement, maxInstIdx, languageManager)

    override fun methodEdgesInitialToFinalApSet(
        methodInitialStatement: CommonInst,
        maxInstIdx: Int,
        languageManager: LanguageManager
    ): MethodEdgesInitialToFinalApSet =
        MethodEdgesInitialToFinalCactusApSet(methodInitialStatement, maxInstIdx, languageManager)

    override fun methodEdgesNDInitialToFinalApSet(
        methodInitialStatement: CommonInst,
        maxInstIdx: Int,
        languageManager: LanguageManager
    ): MethodEdgesNDInitialToFinalApSet =
        MethodEdgesNDInitialToFinalCactusApSet(methodInitialStatement, languageManager, maxInstIdx)

    override fun accessPathSubscription(): MethodAccessPathSubscription =
        MethodCactusAccessPathSubscription()

    override fun sideEffectRequirementApStorage(): SideEffectRequirementApStorage =
        SideEffectRequirementCactusApStorage()

    override fun methodFinalApSummariesStorage(methodInitialStatement: CommonInst): MethodFinalApSummariesStorage =
        MethodFinalTreeApSummariesStorage(methodInitialStatement)

    override fun methodInitialToFinalApSummariesStorage(methodInitialStatement: CommonInst): MethodInitialToFinalApSummariesStorage =
        MethodInitialToFinalApSummaries(methodInitialStatement)

    override fun methodNDInitialToFinalApSummariesStorage(methodInitialStatement: CommonInst): MethodNDInitialToFinalApSummariesStorage =
        MethodNDInitialToFinalCactusApSummariesStorage(methodInitialStatement)

    override fun mostAbstractInitialAp(base: AccessPathBase): InitialFactAp =
        AccessPathWithCycles(base, access = null, exclusions = Empty)

    override fun mostAbstractFinalAp(base: AccessPathBase): FinalFactAp =
        AccessCactus(base, AccessNode.abstractNode(), exclusions = Empty)

    override fun createFinalAp(base: AccessPathBase, exclusions: ExclusionSet): FinalFactAp =
        AccessCactus(base, AccessNode.create(isFinal = true), exclusions)

    override fun createAbstractAp(base: AccessPathBase, exclusions: ExclusionSet): FinalFactAp =
        AccessCactus(base, AccessNode.create(isAbstract = true), exclusions)

    override fun createFinalInitialAp(base: AccessPathBase, exclusions: ExclusionSet): InitialFactAp =
        AccessPathWithCycles(base, access = null, exclusions).prependAccessor(FinalAccessor)

    override fun createSerializer(context: SummarySerializationContext): ApSerializer {
        return CactusSerializer(context)
    }
}

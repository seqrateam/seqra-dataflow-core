package org.seqra.dataflow.ap.ifds.access.automata

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.Accessor
import org.seqra.dataflow.ap.ifds.AnyAccessor
import org.seqra.dataflow.ap.ifds.ExclusionSet
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
import org.seqra.dataflow.ap.ifds.serialization.ApSerializer
import org.seqra.dataflow.ap.ifds.serialization.SummarySerializationContext
import org.seqra.ir.api.common.cfg.CommonInst

class AutomataApManager : ApManager {
    private val interner = AccessorInterner()

    val Accessor.idx: AccessorIdx
        get() = interner.index(this)

    val AccessorIdx.accessor: Accessor
        get() = interner.accessor(this)
            ?: error("Accessor not found: $this")

    val anyAccessorIdx: AccessorIdx by lazy(LazyThreadSafetyMode.NONE) {
        AnyAccessor.idx
    }

    private val emptyGraph by lazy { AccessGraph.newEmptyGraph(this) }

    fun emptyGraph(): AccessGraph = emptyGraph

    override fun initialFactAbstraction(methodInitialStatement: CommonInst): InitialFactAbstraction =
        AutomataInitialFactAbstraction(methodInitialStatement)

    override fun methodEdgesFinalApSet(
        methodInitialStatement: CommonInst,
        maxInstIdx: Int,
        languageManager: LanguageManager
    ): MethodEdgesFinalApSet =
        MethodEdgesFinalAutomataApSet(methodInitialStatement, maxInstIdx, languageManager)

    override fun methodEdgesInitialToFinalApSet(
        methodInitialStatement: CommonInst,
        maxInstIdx: Int,
        languageManager: LanguageManager
    ): MethodEdgesInitialToFinalApSet =
        MethodEdgesInitialToFinalAutomataApSet(methodInitialStatement, maxInstIdx, languageManager)

    override fun methodEdgesNDInitialToFinalApSet(
        methodInitialStatement: CommonInst,
        maxInstIdx: Int,
        languageManager: LanguageManager
    ): MethodEdgesNDInitialToFinalApSet =
        MethodEdgesNDInitialToFinalAutomataApSet(methodInitialStatement, languageManager, maxInstIdx)

    override fun accessPathSubscription(): MethodAccessPathSubscription = MethodAutomataAccessPathSubscription()

    override fun sideEffectRequirementApStorage(): SideEffectRequirementApStorage =
        SideEffectRequirementAutomataApStorage()

    override fun methodFinalApSummariesStorage(methodInitialStatement: CommonInst): MethodFinalApSummariesStorage =
        MethodFinalAutomataApSummariesStorage(methodInitialStatement)

    override fun methodInitialToFinalApSummariesStorage(methodInitialStatement: CommonInst): MethodInitialToFinalApSummariesStorage =
        MethodInitialToFinalAutomataApSummariesStorage(methodInitialStatement)

    override fun methodNDInitialToFinalApSummariesStorage(methodInitialStatement: CommonInst): MethodNDInitialToFinalApSummariesStorage =
        MethodNDInitialToFinalAutomataApSummariesStorage(methodInitialStatement)

    override fun mostAbstractInitialAp(base: AccessPathBase): InitialFactAp =
        AccessGraphInitialFactAp(base, emptyGraph, ExclusionSet.Empty)

    override fun mostAbstractFinalAp(base: AccessPathBase): FinalFactAp =
        AccessGraphFinalFactAp(base, emptyGraph, ExclusionSet.Empty)

    private val finalAp by lazy { emptyGraph.prepend(FinalAccessor.idx) }

    override fun createFinalAp(base: AccessPathBase, exclusions: ExclusionSet): FinalFactAp =
        AccessGraphFinalFactAp(base, finalAp, exclusions)

    override fun createAbstractAp(base: AccessPathBase, exclusions: ExclusionSet): FinalFactAp =
        AccessGraphFinalFactAp(base, emptyGraph, exclusions)

    override fun createFinalInitialAp(base: AccessPathBase, exclusions: ExclusionSet): InitialFactAp =
        AccessGraphInitialFactAp(base, finalAp, exclusions)

    override fun createSerializer(context: SummarySerializationContext): ApSerializer {
        return AccessGraphApSerializer(this, context)
    }
}

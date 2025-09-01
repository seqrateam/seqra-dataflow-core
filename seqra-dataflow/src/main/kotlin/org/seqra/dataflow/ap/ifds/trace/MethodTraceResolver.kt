package org.seqra.dataflow.ap.ifds.trace

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.AnalysisRunner
import org.seqra.dataflow.ap.ifds.AnalysisUnitRunnerManager
import org.seqra.dataflow.ap.ifds.Edge.FactToFact
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdgeSearcher
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges
import org.seqra.dataflow.ap.ifds.MethodEntryPoint
import org.seqra.dataflow.ap.ifds.MethodWithContext
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.analysis.AnalysisManager
import org.seqra.dataflow.ap.ifds.analysis.MethodAnalysisContext
import org.seqra.dataflow.ap.ifds.analysis.MethodCallFactMapper
import org.seqra.dataflow.ap.ifds.trace.MethodCallPrecondition.CallPrecondition
import org.seqra.dataflow.ap.ifds.trace.MethodCallPrecondition.CallPreconditionFact
import org.seqra.dataflow.ap.ifds.trace.MethodSequentPrecondition.SequentPrecondition
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.PartiallyResolvedMergedCallAction.MergedPrimaryCall2StartAction
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.PartiallyResolvedMergedCallAction.MergedPrimaryUnresolvedCallSkip
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.PartiallyResolvedMergedCallAction.MergedRuleAction
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.PartiallyResolvedMergedCallAction.PartiallyResolvedMergedPrimaryCallAction
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction.CallSummary
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction.OtherAction
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction.PrimaryAction
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction.SequentialAction
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction.SourceOtherAction
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction.TraceSummaryEdge
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction.UnresolvedCallSkip
import org.seqra.dataflow.ap.ifds.trace.TaintRulePrecondition.PassRuleCondition
import org.seqra.dataflow.configuration.CommonTaintAction
import org.seqra.dataflow.configuration.CommonTaintAssignAction
import org.seqra.dataflow.configuration.CommonTaintConfigurationItem
import org.seqra.dataflow.configuration.CommonTaintConfigurationSource
import org.seqra.dataflow.graph.MethodInstGraph
import org.seqra.dataflow.util.ConcurrentReadSafeObject2IntMap.NO_VALUE
import org.seqra.dataflow.util.add
import org.seqra.dataflow.util.bitSetOf
import org.seqra.dataflow.util.cartesianProductMapTo
import org.seqra.dataflow.util.forEach
import org.seqra.dataflow.util.getOrCreateIndex
import org.seqra.dataflow.util.object2IntMap
import org.seqra.dataflow.util.toBitSet
import org.seqra.ir.api.common.cfg.CommonAssignInst
import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.common.cfg.CommonValue
import java.util.BitSet
import java.util.LinkedList
import java.util.Objects

class MethodTraceResolver(
    private val runner: AnalysisRunner,
    private val analysisContext: MethodAnalysisContext,
    private val edges: MethodAnalyzerEdges,
    private val graph: MethodInstGraph,
) {
    private val methodEntryPoint: MethodEntryPoint = analysisContext.methodEntryPoint
    private val analysisManager: AnalysisManager get() = runner.analysisManager
    private val manager: AnalysisUnitRunnerManager get() = runner.manager
    private val methodCallFactMapper: MethodCallFactMapper get() = analysisContext.methodCallFactMapper
    private val apManager: ApManager get() = runner.apManager

    enum class TraceKind {
        TraceToFact, // Trace ends within the method
        TraceToFactAfterStatement, // Trace ends within the method, but the fact is after the statement
        SummaryTrace, // Trace summarizes method behaviour
    }

    @Suppress("EqualsOrHashCode")
    data class FullTrace(
        val method: MethodEntryPoint,
        val startEntry: TraceEntry.StartTraceEntry,
        val final: TraceEntry.Final,
        val successors: Map<TraceEntry, Set<TraceEntry>>,
        val traceKind: TraceKind,
    ) {
        override fun hashCode(): Int = Objects.hash(method, final)
    }

    data class SummaryTrace(
        val method: MethodEntryPoint,
        val final: TraceEntry.Final,
        val traceKind: TraceKind,
    )

    sealed interface TraceEdge {
        val fact: InitialFactAp

        fun replaceFact(newFact: InitialFactAp): TraceEdge

        data class SourceTraceEdge(override val fact: InitialFactAp) : TraceEdge {
            override fun replaceFact(newFact: InitialFactAp): SourceTraceEdge = copy(fact = newFact)
        }

        data class MethodTraceEdge(val initialFact: InitialFactAp, override val fact: InitialFactAp) : TraceEdge {
            override fun replaceFact(newFact: InitialFactAp): MethodTraceEdge = copy(fact = newFact)
        }

        data class MethodTraceNDEdge(val initialFacts: Set<InitialFactAp>, override val fact: InitialFactAp) : TraceEdge {
            override fun replaceFact(newFact: InitialFactAp): MethodTraceNDEdge = copy(fact = newFact)
        }
    }

    sealed interface TraceEntryAction {
        sealed interface PrimaryAction : TraceEntryAction

        sealed interface OtherAction : TraceEntryAction

        sealed interface CallAction : TraceEntryAction

        sealed interface CallRuleAction : CallAction {
            val rule: CommonTaintConfigurationItem
            val action: Set<CommonTaintAction>
        }

        sealed interface PassAction : TraceEntryAction {
            val edges: Set<TraceEdge>
        }

        sealed interface SourceAction : TraceEntryAction {
            val sourceEdges: Set<TraceEdge.SourceTraceEdge>
        }

        sealed interface SourcePrimaryAction : SourceAction, PrimaryAction

        sealed interface SourceOtherAction : SourceAction, OtherAction

        sealed interface SequentialAction: TraceEntryAction

        data class Sequential(
            override val edges: Set<TraceEdge>,
        ) : SequentialAction, PrimaryAction, PassAction

        data class SequentialSourceRule(
            override val sourceEdges: Set<TraceEdge.SourceTraceEdge>,
            val rule: CommonTaintConfigurationSource,
            val action: Set<CommonTaintAssignAction>,
        ) : SequentialAction, SourceOtherAction

        data class CallSourceRule(
            override val sourceEdges: Set<TraceEdge.SourceTraceEdge>,
            override val rule: CommonTaintConfigurationSource,
            override val action: Set<CommonTaintAssignAction>
        ) : SourceOtherAction, CallRuleAction

        data class EntryPointSourceRule(
            override val sourceEdges: Set<TraceEdge.SourceTraceEdge>,
            val entryPoint: MethodEntryPoint,
            override val rule: CommonTaintConfigurationSource,
            override val action: Set<CommonTaintAssignAction>
        ) : SourceOtherAction, CallRuleAction

        data class CallRule(
            override val edges: Set<TraceEdge>,
            override val rule: CommonTaintConfigurationItem,
            override val action: Set<CommonTaintAction>
        ) :  CallRuleAction, OtherAction, PassAction

        sealed interface TraceSummaryEdge {
            val edge: TraceEdge

            data class SourceSummary(
                override val edge: TraceEdge.SourceTraceEdge
            ) : TraceSummaryEdge

            data class MethodSummary(
                override val edge: TraceEdge,
                val delta: InitialFactAp.Delta
            ) : TraceSummaryEdge
        }

        data class CallSummary(
            val summaryEdges: Set<TraceSummaryEdge>,
            val summaryTrace: SummaryTrace,
        ) : CallAction, PrimaryAction, PassAction {
            override val edges: Set<TraceEdge>
                get() = summaryEdges.mapTo(hashSetOf()) { it.edge }
        }

        data class CallSourceSummary(
            val summaryEdges: Set<TraceSummaryEdge.SourceSummary>,
            val summaryTrace: SummaryTrace,
        ) : CallAction, SourcePrimaryAction {
            override val sourceEdges: Set<TraceEdge.SourceTraceEdge>
                get() = summaryEdges.mapTo(hashSetOf()) { it.edge }
        }

        data class UnresolvedCallSkip(
            override val edges: Set<TraceEdge>,
        ) : CallAction, PrimaryAction, PassAction
    }

    sealed interface TraceEntry {
        val edges: Set<TraceEdge>
        val statement: CommonInst

        data class Action(
            val primaryAction: PrimaryAction?,
            val otherActions: Set<OtherAction>,
            val unchanged: Set<TraceEdge>,
            override val statement: CommonInst,
        ) : TraceEntry {
            init {
                check(primaryAction != null || otherActions.isNotEmpty()) {
                    "Entry is unchanged"
                }
            }

            override val edges: Set<TraceEdge> get() = buildSet {
                addAll(unchanged)

                if (primaryAction is TraceEntryAction.PassAction) {
                    addAll(primaryAction.edges)
                }

                for (otherAction in otherActions) {
                    if (otherAction is TraceEntryAction.PassAction) {
                        addAll(otherAction.edges)
                    }
                }
            }
        }

        data class Unchanged(
            override val edges: Set<TraceEdge>,
            override val statement: CommonInst,
        ) : TraceEntry

        data class Final(
            override val edges: Set<TraceEdge>,
            override val statement: CommonInst
        ) : TraceEntry

        sealed interface StartTraceEntry: TraceEntry

        data class MethodEntry(
            val facts: Set<InitialFactAp>,
            val entryPoint: MethodEntryPoint,
        ) : StartTraceEntry {
            override val edges: Set<TraceEdge> get() = facts.mapTo(hashSetOf()) {
                TraceEdge.MethodTraceEdge(it, it)
            }

            override val statement: CommonInst
                get() = entryPoint.statement
        }

        data class SourceStartEntry(
            val sourcePrimaryAction: TraceEntryAction.SourcePrimaryAction?,
            val sourceOtherActions: Set<SourceOtherAction>,
            override val statement: CommonInst,
        ) : StartTraceEntry {
            override val edges: Set<TraceEdge.SourceTraceEdge> get() = buildSet {
                sourcePrimaryAction?.let { addAll(it.sourceEdges) }
                sourceOtherActions.forEach { addAll(it.sourceEdges) }
            }
        }
    }

    private class EntryManager {
        private val entries = arrayListOf<TraceEntry>()
        private val entryId = Object2IntOpenHashMap<TraceEntry>().apply { defaultReturnValue(NO_ENTRY) }

        fun entryId(entry: TraceEntry): Int {
            val currentId = entryId.getInt(entry)
            if (currentId != NO_ENTRY) return currentId

            val id = entries.size
            entries.add(entry)
            entryId.put(entry, id)
            return id
        }

        fun entryById(id: Int): TraceEntry = entries[id]

        companion object {
            private const val NO_ENTRY = -1
        }
    }

    private val entryManager = EntryManager()

    private inner class TraceBuilder(val finalEntryId: Int, val cancellation: TraceResolverCancellation) {
        val startEntryIds = BitSet()
        val processedEntryIds = BitSet().also { it.set(finalEntryId) }
        val unprocessedEntryIds = IntArrayList().also { it.add(finalEntryId) }
        val predecessors = Int2ObjectOpenHashMap<BitSet>()
        val successors = Int2ObjectOpenHashMap<BitSet>()

        fun addPredecessor(current: TraceEntry, predecessor: TraceEntry, enqueue: Boolean = true) {
            val currentId = entryManager.entryId(current)
            val predecessorId = entryManager.entryId(predecessor)

            var currentPredecessors = predecessors.get(currentId)
            if (currentPredecessors == null) {
                currentPredecessors = BitSet().also { predecessors.put(currentId, it) }
            }
            currentPredecessors.set(predecessorId)

            var currentSuccessors = successors.get(predecessorId)
            if (currentSuccessors == null) {
                currentSuccessors = BitSet().also { successors.put(predecessorId, it) }
            }
            currentSuccessors.set(currentId)

            if (!processedEntryIds.get(predecessorId)) {
                processedEntryIds.set(predecessorId)

                if (enqueue) {
                    unprocessedEntryIds.add(predecessorId)
                }
            }
        }

        fun addStartEntry(entry: TraceEntry) {
            startEntryIds.set(entryManager.entryId(entry))
        }
    }

    fun resolveIntraProceduralTrace(
        statement: CommonInst,
        facts: Set<InitialFactAp>,
        includeStatement: Boolean = false,
    ): List<SummaryTrace> {
        val edges = facts.map { resolveIntraProceduralTraceEdge(statement, it, includeStatement) }
        return edges.traceToFactSummaryEdges(statement, includeStatement)
    }

    private fun List<List<TraceEdge>>.traceToFactSummaryEdges(
        statement: CommonInst,
        includeStatement: Boolean
    ): List<SummaryTrace> {
        val traceKind = if (includeStatement) TraceKind.TraceToFactAfterStatement else TraceKind.TraceToFact

        val result = mutableListOf<SummaryTrace>()
        this.cartesianProductMapTo {
            val finalEntry = TraceEntry.Final(it.toHashSet(), statement)
            result += SummaryTrace(methodEntryPoint, finalEntry, traceKind)
        }
        return result
    }

    private fun resolveIntraProceduralTraceEdge(
        statement: CommonInst,
        fact: InitialFactAp,
        includeStatement: Boolean
    ): List<TraceEdge> {
        val searcher = object : MethodAnalyzerEdgeSearcher(edges, apManager, analysisManager, analysisContext, graph) {
            override fun matchFact(factAtStatement: FinalFactAp, targetFactPattern: InitialFactAp): Boolean =
                factAtStatement.contains(targetFactPattern)
        }

        val matchingInitialFacts = searcher.searchInitialFacts(statement, fact, includeStatement)

        return matchingInitialFacts.map { initialFacts ->
            when (initialFacts.size) {
                0 -> TraceEdge.SourceTraceEdge(fact)
                1 -> TraceEdge.MethodTraceEdge(initialFacts.first(), fact)
                else -> TraceEdge.MethodTraceNDEdge(initialFacts, fact)
            }
        }
    }

    private fun MethodAnalyzerEdgeSearcher.searchInitialFacts(
        statement: CommonInst,
        fact: InitialFactAp,
        includeStatement: Boolean,
    ): Set<Set<InitialFactAp>> {
        if (!includeStatement) {
            return findMatchingEdgesInitialFacts(statement, fact)
        }

        val statementCall = analysisManager.getCallExpr(statement)
        if (statementCall != null) {
            // todo
            return emptySet()
        } else {
            val preconditionFunction = analysisManager.getMethodSequentPrecondition(
                apManager, analysisContext, statement
            )
            val precondition = preconditionFunction.factPrecondition(fact)
            when (precondition) {
                SequentPrecondition.Unchanged -> {
                    return findMatchingEdgesInitialFacts(statement, fact)
                }

                is SequentPrecondition.Facts -> {
                    val result = hashSetOf<Set<InitialFactAp>>()
                    for (preFact in precondition.facts) {
                        when (preFact) {
                            is MethodSequentPrecondition.SequentSource -> {
                                // todo
                            }

                            is MethodSequentPrecondition.PreconditionFactsForInitialFact -> {
                                preFact.preconditionFacts.forEach {
                                    result += findMatchingEdgesInitialFacts(statement, it)
                                }
                            }
                        }
                    }
                    return result
                }
            }
        }
    }

    fun resolveIntraProceduralTraceFromCall(
        statement: CommonInst,
        calleeEntry: TraceEntry.MethodEntry
    ): List<SummaryTrace> {
        val traceEdges = calleeEntry.facts.flatMap { fact ->
            val mappedFacts = methodCallFactMapper.mapMethodExitToReturnFlowFact(statement, fact)
            mappedFacts.map { resolveIntraProceduralTraceEdge(statement, it, includeStatement = false) }
        }

        return traceEdges.traceToFactSummaryEdges(statement, includeStatement = false)
    }

    fun resolveIntraProceduralFullTrace(
        summaryTrace: SummaryTrace,
        cancellation: TraceResolverCancellation
    ): List<FullTrace> {
        check(summaryTrace.method == methodEntryPoint) { "Incorrect summary trace" }

        val builder = TraceBuilder(entryManager.entryId(summaryTrace.final), cancellation)
        builder.resolveTrace(summaryTrace.traceKind)
        builder.removeUnreachableNodes()
        builder.collapseUnchangedNodes()
        return builder.fullTrace(summaryTrace.traceKind)
    }

    private fun TraceBuilder.removeUnreachableNodes() {
        val reachableFromStart = BitSet()
        val reachableFromFinish = BitSet()

        traverseReachableNodes(reachableFromStart, startEntryIds) { successors.get(it) ?: BitSet() }
        traverseReachableNodes(reachableFromFinish, bitSetOf(finalEntryId)) { predecessors.get(it) ?: BitSet() }

        val reachableNodes = reachableFromStart
        reachableNodes.and(reachableFromFinish)

        val unreachableNodes = successors.keys.toBitSet()
        unreachableNodes.andNot(reachableNodes)

        unreachableNodes.forEach { unreachableEntry ->
            removeUnreachableEntry(unreachableEntry)
        }
    }

    private fun TraceBuilder.removeUnreachableEntry(entryId: Int) {
        val entryPredecessorIds = predecessors.remove(entryId) ?: BitSet()
        val entrySuccessorIds = successors.remove(entryId) ?: BitSet()
        entryPredecessorIds.clear(entryId)
        entrySuccessorIds.clear(entryId)

        entryPredecessorIds.forEach { predecessorId: Int ->
            successors.get(predecessorId)?.clear(entryId)
        }

        entrySuccessorIds.forEach { successorId: Int ->
            predecessors.get(successorId)?.clear(entryId)
        }
    }

    private inline fun TraceBuilder.traverseReachableNodes(reachable: BitSet, initial: BitSet, next: (Int) -> BitSet) {
        initial.forEach { unprocessedEntryIds.add(it) }

        while (unprocessedEntryIds.isNotEmpty()) {
            val entryId = unprocessedEntryIds.removeInt(unprocessedEntryIds.lastIndex)

            if (!reachable.add(entryId)) continue

            next(entryId).forEach { unprocessedEntryIds.add(it) }
        }
    }

    private fun TraceBuilder.collapseUnchangedNodes() {
        processedEntryIds.clear()
        unprocessedEntryIds.add(finalEntryId)

        while (unprocessedEntryIds.isNotEmpty()) {
            val entryId = unprocessedEntryIds.removeInt(unprocessedEntryIds.lastIndex)

            if (!processedEntryIds.add(entryId)) continue

            if (startEntryIds.get(entryId)) continue

            val entryPredecessorIds = predecessors.get(entryId) ?: continue

            val entry = entryManager.entryById(entryId)
            if (entry is TraceEntry.Unchanged) {
                predecessors.remove(entryId)
                entryPredecessorIds.clear(entryId)

                val entrySuccessorIds = successors.remove(entryId) ?: BitSet()
                entrySuccessorIds.clear(entryId)

                entryPredecessorIds.forEach { predecessorId: Int ->
                    val predSuccessors = successors.get(predecessorId)
                    predSuccessors?.clear(entryId)
                    predSuccessors?.or(entrySuccessorIds)
                }

                entrySuccessorIds.forEach { successorId: Int ->
                    val succPredecessors = predecessors.get(successorId)
                    succPredecessors?.clear(entryId)
                    succPredecessors?.or(entryPredecessorIds)
                }
            }

            entryPredecessorIds.forEach { predecessorId: Int ->
                unprocessedEntryIds.add(predecessorId)
            }
        }
    }

    private fun TraceBuilder.fullTrace(traceKind: TraceKind): List<FullTrace> {
        val finalEntry = entryManager.entryById(finalEntryId) as TraceEntry.Final
        val successors = successors()

        val result = mutableListOf<FullTrace>()
        startEntryIds.forEach { entryId: Int ->
            val entry = entryManager.entryById(entryId)
            check(entry is TraceEntry.StartTraceEntry)

            result += FullTrace(methodEntryPoint, entry, finalEntry, successors, traceKind)
        }

        return result
    }

    private fun TraceBuilder.successors(): Map<TraceEntry, Set<TraceEntry>> {
        val allSuccessors = hashMapOf<TraceEntry, MutableSet<TraceEntry>>()
        for ((entryId, entryPredecessorIds) in predecessors) {
            val entry = entryManager.entryById(entryId)

            entryPredecessorIds.forEach { predecessorId: Int ->
                val predecessor = entryManager.entryById(predecessorId)
                val successors = allSuccessors.getOrPut(predecessor, ::hashSetOf)
                successors.add(entry)
            }
        }
        return allSuccessors
    }

    private fun TraceBuilder.resolveTrace(traceKind: TraceKind) {
        while (unprocessedEntryIds.isNotEmpty() && cancellation.isActive) {
            val entryId = unprocessedEntryIds.removeInt(unprocessedEntryIds.lastIndex)
            val entry = entryManager.entryById(entryId)
            processTraceEntry(entry, traceKind)
        }
    }

    private fun TraceBuilder.processTraceEntry(entry: TraceEntry, traceKind: TraceKind) {
        if (entry is TraceEntry.StartTraceEntry) {
            addStartEntry(entry)
            return
        }

        if (entry is TraceEntry.Final) {
            when (traceKind) {
                TraceKind.TraceToFact -> {
                    // We have fact BEFORE entry.statement, no need to propagate
                }

                TraceKind.TraceToFactAfterStatement,
                TraceKind.SummaryTrace -> {
                    // We have fact AFTER entry.statement
                    propagateEntryNew(entry.statement, entry, skipFactCheck = true)
                    return
                }
            }
        }

        if (entry.statement == methodEntryPoint.statement) {
            propagateEntryToMethodEntryPoint(entry)
            return
        }

        graph.forEachPredecessor(entry.statement) {
            propagateEntryNew(it, entry)
        }
    }

    private fun TraceBuilder.propagateEntryToMethodEntryPoint(
        entry: TraceEntry
    ) {
        val entryEdges = hashSetOf<TraceEdge>()
        val sources = hashSetOf<SourceOtherAction>()

        for (edge in entry.edges) {
            // We always have fact before entry point
            if (!containsEntryEdge(entry.statement, edge)) return

            when (edge) {
                is TraceEdge.MethodTraceEdge -> {
                    entryEdges.add(edge)
                }

                is TraceEdge.MethodTraceNDEdge -> {
                    entryEdges.add(edge)
                }

                is TraceEdge.SourceTraceEdge -> {
                    val preconditionFunction = analysisManager.getMethodStartPrecondition(apManager, analysisContext)
                    preconditionFunction.factPrecondition(edge.fact).forEach {
                        val source = TraceEntryAction.EntryPointSourceRule(
                            setOf(edge), methodEntryPoint, it.rule, it.action
                        )
                        sources.add(source)
                    }
                }
            }
        }

        if (entryEdges.isEmpty()) {
            if (sources.isEmpty()) return

            addPredecessor(
                entry,
                TraceEntry.SourceStartEntry(sourcePrimaryAction = null, sources, methodEntryPoint.statement)
            )
        } else {
            val preStartEntry = if (sources.isNotEmpty()) {
                TraceEntry.Action(primaryAction = null, sources, entryEdges, methodEntryPoint.statement)
                    .also { addPredecessor(entry, it, enqueue = false) }
            } else {
                entry
            }

            val entryFacts = entryEdges.flatMapTo(hashSetOf()) {
                when (it) {
                    is TraceEdge.MethodTraceEdge -> listOf(it.initialFact)
                    is TraceEdge.MethodTraceNDEdge -> it.initialFacts
                    is TraceEdge.SourceTraceEdge -> error("impossible")
                }
            }

            val startEntry = TraceEntry.MethodEntry(entryFacts, methodEntryPoint)
            addPredecessor(preStartEntry, startEntry)
        }
    }

    private fun TraceBuilder.propagateEntryNew(
        statement: CommonInst,
        entry: TraceEntry,
        skipFactCheck: Boolean = false,
    ) {
        val statementCall = analysisManager.getCallExpr(statement)
        if (statementCall != null) {
            val returnValue: CommonValue? = (statement as? CommonAssignInst)?.lhv

            val preconditionFunction = analysisManager.getMethodCallPrecondition(
                apManager, analysisContext, returnValue, statementCall, statement
            )

            val unchangedEdges = hashSetOf<TraceEdge>()
            val callEdges = mutableListOf<List<PartiallyResolvedCallAction>>()

            for (edge in entry.edges) {
                val precondition = preconditionFunction.factPrecondition(edge.fact)
                when (precondition) {
                    CallPrecondition.Unchanged -> {
                        unchangedEdges.add(edge)
                        continue
                    }

                    is CallPrecondition.Facts -> {
                        val callActions = mutableListOf<PartiallyResolvedCallAction>()
                        precondition.facts.forEach {
                            val initialEdge = edge.replaceFact(it.initialFact)
                            if (!skipFactCheck && !containsEntryEdge(entry.statement, initialEdge)) {
                                return@forEach
                            }

                            callActions.propagateCall(edge, it.preconditionFacts)
                        }

                        if (callActions.isEmpty()) {
                            // fact has no preconditions
                            return
                        }

                        callEdges.add(callActions)
                    }
                }
            }

            if (callEdges.isEmpty()) {
                addPredecessor(entry, TraceEntry.Unchanged(unchangedEdges, statement))
                return
            }

            val callActions = mergeCallActionsCombinations(callEdges, statement, statementCall)
            val resolvedCallActions = resolveCallActions(preconditionFunction, statement, callActions)

            for ((callActionPrimary, callActionOther) in resolvedCallActions) {
                val action = TraceEntry.Action(callActionPrimary, callActionOther, unchangedEdges, statement)
                addPredecessorAction(entry, action)
            }
        } else {
            val preconditionFunction = analysisManager.getMethodSequentPrecondition(
                apManager, analysisContext, statement
            )

            val unchangedEdges = hashSetOf<TraceEdge>()
            val sequentActions = mutableListOf<List<SequentialAction>>()

            for (edge in entry.edges) {
                val precondition = preconditionFunction.factPrecondition(edge.fact)

                when (precondition) {
                    SequentPrecondition.Unchanged -> {
                        unchangedEdges.add(edge)
                    }

                    is SequentPrecondition.Facts -> {
                        val actions = mutableListOf<SequentialAction>()

                        precondition.facts.forEach {
                            val initialEdge = edge.replaceFact(it.fact)
                            if (!skipFactCheck && !containsEntryEdge(entry.statement, initialEdge)) {
                                return@forEach
                            }

                            when (it) {
                                is MethodSequentPrecondition.PreconditionFactsForInitialFact -> {
                                    it.preconditionFacts.mapTo(actions) { fact ->
                                        TraceEntryAction.Sequential(setOf(edge.replaceFact(fact)))
                                    }
                                }

                                is MethodSequentPrecondition.SequentSource -> {
                                    if (initialEdge is TraceEdge.SourceTraceEdge) {
                                        actions += TraceEntryAction.SequentialSourceRule(
                                            setOf(initialEdge), it.rule.rule, it.rule.action
                                        )
                                    }
                                }
                            }
                        }

                        if (actions.isEmpty()) {
                            // fact has no preconditions
                            return
                        }

                        sequentActions.add(actions)
                    }
                }
            }

            if (sequentActions.isEmpty()) {
                addPredecessor(entry, TraceEntry.Unchanged(unchangedEdges, statement))
                return
            }

            val sequentActionsCombination = mergeSequentEdgeCombinations(sequentActions)
            for ((primaryAction, otherActions) in sequentActionsCombination) {
                addPredecessorAction(
                    entry, TraceEntry.Action(primaryAction, otherActions, unchangedEdges, statement)
                )
            }
        }
    }

    private fun TraceBuilder.addPredecessorAction(entry: TraceEntry, action: TraceEntry.Action) {
        val startOrAction = tryCreateSourceStart(action) ?: action
        addPredecessor(entry, startOrAction)
    }

    private fun tryCreateSourceStart(action: TraceEntry.Action): TraceEntry.SourceStartEntry? {
        if (action.unchanged.isNotEmpty()) return null

        val primary = action.primaryAction
        if (primary !is TraceEntryAction.SourcePrimaryAction?) return null

        val sourceOther = action.otherActions.filterIsInstanceTo<SourceOtherAction, _>(hashSetOf())
        if (sourceOther.size != action.otherActions.size) return null

        return TraceEntry.SourceStartEntry(primary, sourceOther, action.statement)
    }

    private fun mergeSequentEdgeCombinations(actions: List<List<SequentialAction>>): List<Pair<PrimaryAction?, Set<OtherAction>>> {
        val result = mutableListOf<Pair<PrimaryAction?, Set<OtherAction>>>()
        actions.cartesianProductMapTo { actionCombination ->
            val sequential = hashSetOf<TraceEdge>()
            val rules = hashSetOf<TraceEntryAction.SequentialSourceRule>()

            for (action in actionCombination) {
                when (action) {
                    is TraceEntryAction.Sequential -> sequential.addAll(action.edges)
                    is TraceEntryAction.SequentialSourceRule -> rules.add(action)
                }
            }

            val primaryAction = sequential.takeIf { it.isNotEmpty() }?.let { TraceEntryAction.Sequential(it) }
            result += primaryAction to rules
        }
        return result
    }

    private fun mergeCallActionsCombinations(
        callActions: List<List<PartiallyResolvedCallAction>>,
        statement: CommonInst,
        statementCall: CommonCallExpr
    ): MutableList<Pair<PartiallyResolvedMergedPrimaryCallAction?, Set<MergedRuleAction>>> {
        val callees by lazy {
            runner.methodCallResolver.resolvedMethodCalls(methodEntryPoint, statementCall, statement)
                .flatMap { methodEntryPoints(it) }
        }

        val result = mutableListOf<Pair<PartiallyResolvedMergedPrimaryCallAction?, Set<MergedRuleAction>>>()
        callActions.cartesianProductMapTo { actions ->
            val mergedActions = mergeCallActions(actions) { callees }
            result.addAll(mergedActions)
        }
        return result
    }

    private fun mergeCallActions(
        callActions: Array<PartiallyResolvedCallAction>,
        resolveMethodCallees: () -> List<MethodEntryPoint>
    ): List<Pair<PartiallyResolvedMergedPrimaryCallAction?, Set<MergedRuleAction>>> {
        val rules = hashSetOf<PartiallyResolvedCallAction.CallRule>()
        val summary = hashSetOf<PartiallyResolvedCallAction.Call2Start>()

        for (action in callActions) {
            when (action) {
                is PartiallyResolvedCallAction.CallRule -> rules.add(action)
                is PartiallyResolvedCallAction.Call2Start -> summary.add(action)
            }
        }

        val mergedRules = mergeCallRules(rules)

        if (summary.isEmpty()) {
            return listOf(null to mergedRules)
        }

        val mergedCallSummaries = mergeCallSummaries(summary, resolveMethodCallees())
            ?: return listOf(null to mergedRules)

        return mergedCallSummaries.map { it to mergedRules }
    }

    private fun mergeCallRules(callRules: HashSet<PartiallyResolvedCallAction.CallRule>): Set<MergedRuleAction> {
        if (callRules.isEmpty()) return emptySet()

        val sourceRules = hashMapOf<CommonTaintConfigurationSource, MutableSet<Pair<CommonTaintAssignAction, TraceEdge>>>()
        val passRules = hashMapOf<CommonTaintConfigurationItem, MutableMap<PassRuleCondition, MutableSet<Pair<CommonTaintAction, TraceEdge>>>>()

        for (unresolvedRule in callRules) {
            when (val rule = unresolvedRule.rule) {
                is TaintRulePrecondition.Pass -> passRules
                    .getOrPut(rule.rule, ::hashMapOf)
                    .getOrPut(rule.condition, ::hashSetOf)
                    .addAll(rule.action.map { it to unresolvedRule.currentEdge })

                is TaintRulePrecondition.Source -> sourceRules
                    .getOrPut(rule.rule, ::hashSetOf)
                    .addAll(rule.action.map { it to unresolvedRule.currentEdge })
            }
        }

        val result = hashSetOf<MergedRuleAction>()
        for ((rule, actionWithEdge) in sourceRules) {
            val action = actionWithEdge.mapTo(hashSetOf()) { it.first }
            val edges = actionWithEdge.mapTo(hashSetOf()) { it.second }
            result += MergedRuleAction(edges, TaintRulePrecondition.Source(rule, action))
        }

        for ((rule, conditionedActions) in passRules) {
            for ((condition, actionWithEdge) in conditionedActions) {
                val action = actionWithEdge.mapTo(hashSetOf()) { it.first }
                val edges = actionWithEdge.mapTo(hashSetOf()) { it.second }
                result += MergedRuleAction(edges, TaintRulePrecondition.Pass(rule, action, condition))
            }
        }

        return result
    }

    private fun mergeCallSummaries(
        callSummaries: Set<PartiallyResolvedCallAction.Call2Start>,
        callees: List<MethodEntryPoint>
    ): Set<PartiallyResolvedMergedPrimaryCallAction>? {
        if (callees.isEmpty()) {
            // Drop fact if it is mapped to the method return value
            val nonReturnSummaries = callSummaries.filter { it.call2Start.startFactBase != AccessPathBase.Return }
            if (nonReturnSummaries.isEmpty()) return null

            val nonReturnEdges = nonReturnSummaries.mapTo(hashSetOf()) { it.currentEdge }
            return setOf(MergedPrimaryUnresolvedCallSkip(UnresolvedCallSkip(nonReturnEdges)))

        }

        return callees.mapTo(hashSetOf()) {
            MergedPrimaryCall2StartAction(it, callSummaries)
        }
    }

    private sealed interface PartiallyResolvedCallAction {
        data class CallRule(
            val currentEdge: TraceEdge,
            val rule: TaintRulePrecondition
        ) : PartiallyResolvedCallAction

        data class Call2Start(
            val currentEdge: TraceEdge,
            val call2Start: CallPreconditionFact.CallToStart,
        ): PartiallyResolvedCallAction
    }

    private sealed interface PartiallyResolvedMergedCallAction {
        sealed interface PartiallyResolvedMergedPrimaryCallAction: PartiallyResolvedMergedCallAction

        data class MergedPrimaryCall2StartAction(
            val calleeEntryPoint: MethodEntryPoint,
            val call2Start: Set<PartiallyResolvedCallAction.Call2Start>,
        ) : PartiallyResolvedMergedPrimaryCallAction

        data class MergedPrimaryUnresolvedCallSkip(
            val action: UnresolvedCallSkip
        ) : PartiallyResolvedMergedPrimaryCallAction

        data class MergedRuleAction(
            val currentEdges: Set<TraceEdge>,
            val rule: TaintRulePrecondition
        ) : PartiallyResolvedMergedCallAction
    }

    private fun MutableList<PartiallyResolvedCallAction>.propagateCall(
        currentEdge: TraceEdge,
        preconditionFacts: List<CallPreconditionFact>
    ) {
        for (fact in preconditionFacts) {
            when (fact) {
                is CallPreconditionFact.CallToReturnTaintRule -> {
                    if (fact.precondition is TaintRulePrecondition.Source && currentEdge !is TraceEdge.SourceTraceEdge) {
                        // We search for pass-rule, not source rule
                        continue
                    }

                    this += PartiallyResolvedCallAction.CallRule(currentEdge, fact.precondition)
                }

                is CallPreconditionFact.CallToStart -> {
                    this += PartiallyResolvedCallAction.Call2Start(currentEdge, fact)
                }
            }
        }
    }

    private fun resolveCallActions(
        preconditionFunction: MethodCallPrecondition,
        statement: CommonInst,
        callActions: List<Pair<PartiallyResolvedMergedPrimaryCallAction?, Set<MergedRuleAction>>>,
    ): List<Pair<PrimaryAction?, Set<OtherAction>>> {
        val result = mutableListOf<Pair<PrimaryAction?, Set<OtherAction>>>()
        for ((primaryAction, ruleActions) in callActions) {
            val resolvedPrimaryAction = when (primaryAction) {
                null -> null
                is MergedPrimaryUnresolvedCallSkip -> listOf(primaryAction.action)
                is MergedPrimaryCall2StartAction -> {
                    resolveCallSummary(statement, primaryAction.calleeEntryPoint, primaryAction.call2Start)
                }
            }

            if (ruleActions.isEmpty()) {
                resolvedPrimaryAction?.mapTo(result) { it to emptySet() }
                continue
            }

            val resolvedRuleActions = ruleActions.map {
                resolveCallRule(it.currentEdges, it.rule, preconditionFunction, statement)
            }

            resolvedRuleActions.cartesianProductMapTo { ruleActionGroup ->
                val ruleActionSet = ruleActionGroup.toHashSet()

                if (resolvedPrimaryAction == null) {
                    result.add(null to ruleActionSet)
                } else {
                    resolvedPrimaryAction.mapTo(result) { it to ruleActionSet }
                }
            }
        }
        return result
    }

    private fun resolveCallSummary(
        statement: CommonInst,
        callee: MethodEntryPoint,
        call2Start: Set<PartiallyResolvedCallAction.Call2Start>,
    ): List<PrimaryAction> {
        val resultSummaries = mutableListOf<List<CallSummary>>()
        for (action in call2Start) {
            val edgeSummaries = mutableListOf<CallSummary>()

            val currentEdge = action.currentEdge
            if (currentEdge is TraceEdge.SourceTraceEdge) {
                edgeSummaries.resolveCallSourceSummary(currentEdge, callee, action.call2Start)
            }

            edgeSummaries.resolveCallPassSummary(currentEdge, callee, action.call2Start, statement)

            if (edgeSummaries.isEmpty()) return emptyList()

            resultSummaries.add(edgeSummaries)
        }

        val resultActions = mutableListOf<PrimaryAction>()
        resultSummaries.cartesianProductMapTo { summaryGroup ->
            resultActions += mergeCallSummary(summaryGroup) ?: return@cartesianProductMapTo
        }
        return resultActions
    }

    private fun mergeCallSummary(callSummaries: Array<CallSummary>): PrimaryAction? {
        check(callSummaries.all { it.summaryTrace.traceKind == TraceKind.SummaryTrace })

        val callee = callSummaries.first().summaryTrace.method

        val exitStatement = callSummaries.first().summaryTrace.final.statement
        if (callSummaries.any { it.summaryTrace.final.statement != exitStatement }) return null

        val finalEdges = hashSetOf<TraceEdge>()
        val summaryEdges = hashSetOf<TraceSummaryEdge>()

        for (summary in callSummaries) {
            summaryEdges += summary.summaryEdges
            finalEdges += summary.summaryTrace.final.edges
        }

        val summaryTraceFinal = TraceEntry.Final(finalEdges, exitStatement)
        val summaryTrace = SummaryTrace(callee, summaryTraceFinal, TraceKind.SummaryTrace)

        val sourceSummaryEdges = summaryEdges.filterIsInstanceTo<TraceSummaryEdge.SourceSummary, _>(hashSetOf())
        val summaryAction = if (sourceSummaryEdges.size == summaryEdges.size) {
            TraceEntryAction.CallSourceSummary(sourceSummaryEdges, summaryTrace)
        } else {
            CallSummary(summaryEdges, summaryTrace)
        }

        return summaryAction
    }

    private fun resolveCallRule(
        currentEdges: Set<TraceEdge>,
        rule: TaintRulePrecondition,
        preconditionFunction: MethodCallPrecondition,
        statement: CommonInst,
    ): List<OtherAction> {
        when (rule) {
            is TaintRulePrecondition.Source -> {
                val sourceEdges = currentEdges.filterIsInstanceTo<TraceEdge.SourceTraceEdge, _>(hashSetOf())
                check(sourceEdges.size == currentEdges.size) {
                    "Unexpected non-source edge"
                }

                return listOf(TraceEntryAction.CallSourceRule(sourceEdges, rule.rule, rule.action))
            }

            is TaintRulePrecondition.Pass -> {
                val conditionFacts = preconditionFunction.resolvePassRuleCondition(rule.condition)
                return conditionFacts.flatMap {
                    resolvePassCallRulePrecondition(currentEdges, statement, rule, it.facts)
                }
            }
        }
    }

    private fun resolvePassCallRulePrecondition(
        currentEdges: Set<TraceEdge>,
        statement: CommonInst,
        rule: TaintRulePrecondition.Pass,
        facts: List<InitialFactAp>,
    ): List<TraceEntryAction.CallRule> {
        when (facts.size) {
            0 -> error("impossible")
            1 -> {
                val initialFacts = currentEdges.flatMap {
                    when (it) {
                        is TraceEdge.SourceTraceEdge -> listOf(null)
                        is TraceEdge.MethodTraceEdge -> listOf(it.initialFact)
                        is TraceEdge.MethodTraceNDEdge -> it.initialFacts
                    }
                }.distinct()

                if (initialFacts.size != 1) {
                    // unxpected different initial facts
                    return emptyList()
                }

                val initialFact = initialFacts.first()
                val edge = if (initialFact == null) {
                    TraceEdge.SourceTraceEdge(facts.first())
                } else {
                    TraceEdge.MethodTraceEdge(initialFact, facts.first())
                }

                return listOf(
                    TraceEntryAction.CallRule(setOf(edge), rule.rule, rule.action)
                )
            }

            else -> {
                val result = mutableListOf<TraceEntryAction.CallRule>()

                val allFactEdges = facts.map {
                    resolveIntraProceduralTraceEdge(statement, it, includeStatement = false)
                }

                val currentInitialFacts = object2IntMap<InitialFactAp?>()
                currentEdges.forEach { edge ->
                    when (edge) {
                        is TraceEdge.SourceTraceEdge -> {
                            currentInitialFacts.getOrCreateIndex(null) { return@forEach }
                        }

                        is TraceEdge.MethodTraceEdge -> {
                            currentInitialFacts.getOrCreateIndex(edge.initialFact.replaceExclusions(ExclusionSet.Universe)) { return@forEach }
                        }

                        is TraceEdge.MethodTraceNDEdge -> edge.initialFacts.forEachIndexed { _, it ->
                            currentInitialFacts.getOrCreateIndex(it.replaceExclusions(ExclusionSet.Universe)) { return@forEachIndexed }
                        }
                    }
                }

                allFactEdges.cartesianProductMapTo { edgeGroup ->
                    val unmatchedInitials = BitSet(currentInitialFacts.size)
                    unmatchedInitials.set(0, currentInitialFacts.size)

                    for (edge in edgeGroup) {
                        when (edge) {
                            is TraceEdge.SourceTraceEdge -> {
                                val idx = currentInitialFacts.getInt(null)
                                if (idx != NO_VALUE) {
                                    unmatchedInitials.clear(idx)
                                }
                            }

                            is TraceEdge.MethodTraceEdge -> {
                                val idx = currentInitialFacts.getInt(edge.initialFact.replaceExclusions(ExclusionSet.Universe))
                                if (idx != NO_VALUE) {
                                    unmatchedInitials.clear(idx)
                                }
                            }

                            is TraceEdge.MethodTraceNDEdge -> {
                                edge.initialFacts.forEach {
                                    val idx = currentInitialFacts.getInt(it.replaceExclusions(ExclusionSet.Universe))
                                    if (idx != NO_VALUE) {
                                        unmatchedInitials.clear(idx)
                                    }
                                }
                            }
                        }
                    }

                    if (!unmatchedInitials.isEmpty) {
                        return@cartesianProductMapTo
                    }

                    result += TraceEntryAction.CallRule(edgeGroup.toHashSet(), rule.rule, rule.action)
                }

                return result
            }
        }
    }

    private fun MutableList<CallSummary>.resolveCallPassSummary(
        currentEdge: TraceEdge,
        callee: MethodEntryPoint,
        startFact: CallPreconditionFact.CallToStart,
        statement: CommonInst
    ) {
        val resolvedCallSummaries = mutableListOf<CallSummary>()

        val methodSummaries = manager.findFactToFactSummaryEdges(callee, startFact.startFactBase)
        val callerFact = startFact.callerFact
        for (summaryEdge in methodSummaries) {
            val mappedSummaryFact = summaryEdge.factAp.rebase(callerFact.base)
            val deltas = callerFact.splitDelta(mappedSummaryFact)

            if (deltas.isEmpty()) continue

            // it is ok to map call arguments via exit2return
            val mappedSummaryInitial = methodCallFactMapper.mapMethodExitToReturnFlowFact(
                statement, summaryEdge.initialFactAp
            )

            for ((matchedEntryFact, delta) in deltas) {
                // todo: remove this check?
                if (!mappedSummaryFact.contains(matchedEntryFact)) continue

                for (mappedSummaryInitialFact in mappedSummaryInitial) {
                    val precondition = mappedSummaryInitialFact
                        .concat(delta)
                        .replaceExclusions(callerFact.exclusions)

                    resolvedCallSummaries.addCallSummaryEntry(
                        currentTraceEdge = currentEdge,
                        precondition = precondition,
                        preconditionDelta = delta,
                        callee = callee,
                        summaryFinalFact = matchedEntryFact,
                        summaryEdge = summaryEdge,
                    )
                }
            }
        }

        val weakestCallSummaries = selectWeakestEntries(resolvedCallSummaries)
        this += weakestCallSummaries

        val methodNdSummaries = manager.findFactNDSummaryEdges(callee, startFact.startFactBase)
        for (summaryEdge in methodNdSummaries) {
            val mappedSummaryFact = summaryEdge.factAp.rebase(callerFact.base)

            if (!mappedSummaryFact.contains(callerFact)) continue

            val mappedSummaryInitialFacts = summaryEdge.initialFacts.map {
                methodCallFactMapper.mapMethodExitToReturnFlowFact(statement, it)
            }

            mappedSummaryInitialFacts.cartesianProductMapTo { mappedFactGroup ->
                val preconditions = mappedFactGroup.toHashSet()

                val mappedFinalFact = callerFact
                    .rebase(summaryEdge.factAp.base)
                    .replaceExclusions(summaryEdge.factAp.exclusions)

                val traceSummaryEdge = TraceEdge.MethodTraceNDEdge(summaryEdge.initialFacts, mappedFinalFact)
                val calleeTrace = SummaryTrace(
                    final = TraceEntry.Final(setOf(traceSummaryEdge), summaryEdge.statement),
                    method = callee,
                    traceKind = TraceKind.SummaryTrace,
                )

                val callSummaries = preconditions.mapTo(hashSetOf()) {
                    TraceSummaryEdge.MethodSummary(currentEdge.replaceFact(it), emptyDelta)
                }

                this += CallSummary(callSummaries, calleeTrace)
            }
        }
    }

    private val emptyDelta by lazy { apManager.emptyDelta() }

    private fun ApManager.emptyDelta(): InitialFactAp.Delta {
        val fap = mostAbstractFinalAp(AccessPathBase.This)
        val iap = mostAbstractInitialAp(AccessPathBase.This)
        return iap.splitDelta(fap)
            .map { it.second }
            .firstOrNull { it.isEmpty }
            ?: error("Empty delta expected")
    }

    private fun MutableList<CallSummary>.resolveCallSourceSummary(
        currentEdge: TraceEdge.SourceTraceEdge,
        callee: MethodEntryPoint,
        startFact: CallPreconditionFact.CallToStart
    ) {
        val relevantSummaryEdges = manager.findZeroToFactSummaryEdges(callee, startFact.startFactBase)
        for (summaryEdge in relevantSummaryEdges) {
            val mappedSummaryFact = summaryEdge.factAp.rebase(startFact.callerFact.base)
            if (!mappedSummaryFact.contains(startFact.callerFact)) continue

            val summaryEdgeTrace = TraceEdge.SourceTraceEdge(startFact.callerFact.rebase(startFact.startFactBase))
            val summaryTrace = SummaryTrace(
                method = callee,
                final = TraceEntry.Final(setOf(summaryEdgeTrace), summaryEdge.statement),
                traceKind = TraceKind.SummaryTrace
            )

            val callSummary = TraceSummaryEdge.SourceSummary(currentEdge)
            this += CallSummary(setOf(callSummary), summaryTrace)
        }
    }

    private fun selectWeakestEntries(entries: List<CallSummary>): List<CallSummary> {
        val selectedEntries = LinkedList<CallSummary>()
        for (summary in entries) {
            addWeakestEntry(summary, selectedEntries)
        }
        return selectedEntries
    }

    private fun addWeakestEntry(entry: CallSummary, selectedEntries: LinkedList<CallSummary>) {
        val entryFact = entry.edges.single().fact
        val iter = selectedEntries.listIterator()

        while (iter.hasNext()) {
            val selectedEntry = iter.next()
            val selectedFact = selectedEntry.edges.single().fact

            // Entry fact is stronger than already added selected fact
            if (entryFact.contains(selectedFact)) {
                return
            }

            // Selected fact is stronger
            if (selectedFact.contains(entryFact)) {
                iter.remove()
            }
        }

        selectedEntries.add(entry)
    }

    private fun MutableList<CallSummary>.addCallSummaryEntry(
        currentTraceEdge: TraceEdge,
        precondition: InitialFactAp,
        preconditionDelta: InitialFactAp.Delta,
        callee: MethodEntryPoint,
        summaryFinalFact: InitialFactAp,
        summaryEdge: FactToFact,
    ) {
        val mappedFinalFact = summaryFinalFact
            .rebase(summaryEdge.factAp.base)
            .replaceExclusions(summaryEdge.factAp.exclusions)

        val traceSummaryEdge = TraceEdge.MethodTraceEdge(summaryEdge.initialFactAp, mappedFinalFact)
        val calleeTrace = SummaryTrace(
            final = TraceEntry.Final(setOf(traceSummaryEdge), summaryEdge.statement),
            method = callee,
            traceKind = TraceKind.SummaryTrace,
        )

        val callSummary = TraceSummaryEdge.MethodSummary(
            currentTraceEdge.replaceFact(precondition),
            preconditionDelta
        )

        this += CallSummary(setOf(callSummary), calleeTrace)
    }

    private fun methodEntryPoints(method: MethodWithContext): Sequence<MethodEntryPoint> =
        runner.graph.entryPoints(method.method).map { MethodEntryPoint(method.ctx, it) }

    private fun containsEntryEdge(entryStatement: CommonInst, entryEdge: TraceEdge): Boolean {
        when (entryEdge) {
            is TraceEdge.SourceTraceEdge -> {
                val entryFacts = edges.allZeroToFactFactsAtStatement(entryStatement, entryEdge.fact)
                return entryFacts.any { statementFact -> statementFact.contains(entryEdge.fact) }
            }

            is TraceEdge.MethodTraceEdge -> {
                val entryFacts = edges.allFactToFactFactsAtStatement(entryStatement, entryEdge.initialFact, entryEdge.fact)
                return entryFacts.any { statementFact -> statementFact.contains(entryEdge.fact) }
            }

            is TraceEdge.MethodTraceNDEdge -> {
                val entryFacts = edges.allNDFactToFactFactsAtStatement(entryStatement, entryEdge.initialFacts, entryEdge.fact)
                return entryFacts.any { statementFact -> statementFact.contains(entryEdge.fact) }
            }
        }
    }
}

package org.seqra.dataflow.ap.ifds.trace

import org.seqra.dataflow.ap.ifds.MethodEntryPoint
import org.seqra.dataflow.ap.ifds.SummaryEdgeSubscriptionManager.MethodEntryPointCaller
import org.seqra.dataflow.ap.ifds.TaintAnalysisUnitRunner
import org.seqra.dataflow.ap.ifds.TaintAnalysisUnitRunnerManager
import org.seqra.dataflow.ap.ifds.taint.TaintSinkTracker
import org.seqra.dataflow.ap.ifds.taint.TaintSinkTracker.TaintVulnerability
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntry.MethodEntry
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntry.SourceStartEntry
import org.seqra.dataflow.ap.ifds.trace.MethodTraceResolver.TraceEntryAction
import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonInst

class TraceResolver(
    private val entryPointMethods: Set<CommonMethod>,
    private val manager: TaintAnalysisUnitRunnerManager,
    private val params: Params,
    private val cancellation: TraceResolverCancellation
) {
    data class Params(
        val resolveEntryPointToStartTrace: Boolean = true,
        val startToSourceTraceResolutionLimit: Int? = null,
        val startToSinkTraceResolutionLimit: Int? = null,
    )

    data class Trace(
        val entryPointToStart: EntryPointToStartTrace?,
        val sourceToSinkTrace: SourceToSinkTrace,
    )

    data class EntryPointToStartTrace(
        val entryPoints: Set<EntryPointTraceNode>,
        val successors: Map<TraceNode, Set<TraceNode>>
    )

    data class SourceToSinkTrace(
        val startNodes: Set<SourceToSinkTraceNode>,
        val sinkNodes: Set<SourceToSinkTraceNode>,
        val successors: Map<InterProceduralTraceNode, Set<InterProceduralCall>>
    ) {
        fun findSuccessors(
            node: InterProceduralTraceNode, kind: CallKind, statement: CommonInst
        ) = successors[node]?.filter { it.kind == kind && it.statement == statement }.orEmpty()

        fun findSuccessors(
            node: InterProceduralTraceNode, kind: CallKind, statement: CommonInst, trace: MethodTraceResolver.SummaryTrace
        ) = successors[node]?.filter { it.kind == kind && it.statement == statement && it.summary == trace }.orEmpty()
    }

    sealed interface TraceNode

    sealed interface EntryPointToStartTraceNode : TraceNode

    data class CallTraceNode(val statement: CommonInst, val methodEntryPoint: MethodEntryPoint) :
        EntryPointToStartTraceNode
    data class EntryPointTraceNode(val method: CommonMethod) : EntryPointToStartTraceNode

    sealed interface SourceToSinkTraceNode : TraceNode {
        val methodEntryPoint: MethodEntryPoint
    }

    data class SimpleTraceNode(
        val statement: CommonInst,
        override val methodEntryPoint: MethodEntryPoint
    ) : SourceToSinkTraceNode

    sealed interface InterProceduralTraceNode: SourceToSinkTraceNode

    data class InterProceduralFullTraceNode(
        val trace: MethodTraceResolver.FullTrace
    ) : InterProceduralTraceNode {
        override val methodEntryPoint: MethodEntryPoint
            get() = trace.method
    }

    data class InterProceduralSummaryTraceNode(
        val trace: MethodTraceResolver.SummaryTrace
    ) : InterProceduralTraceNode {
        override val methodEntryPoint: MethodEntryPoint
            get() = trace.method
    }

    enum class CallKind {
        CallToSource, CallToSink
    }

    data class InterProceduralCall(
        val kind: CallKind,
        val statement: CommonInst,
        val summary: MethodTraceResolver.SummaryTrace,
        val node: InterProceduralTraceNode
    )

    fun resolveTrace(vulnerability: TaintVulnerability): Trace {
        when (vulnerability) {
            is TaintSinkTracker.TaintVulnerabilityUnconditional -> {
                val node = SimpleTraceNode(vulnerability.statement, vulnerability.methodEntryPoint)
                val entryPointToStart = resolveEntryPointToStartTrace(setOf(node))
                val sourceToSinkTrace = SourceToSinkTrace(setOf(node), setOf(node), emptyMap())
                return Trace(entryPointToStart, sourceToSinkTrace)
            }

            is TaintSinkTracker.TaintVulnerabilityWithFact -> {
                val builder = InterProceduralTraceGraphBuilder()

                withMethodRunner(vulnerability.methodEntryPoint) {
                    val traces = resolveIntraProceduralTraceSummary(
                        vulnerability.methodEntryPoint,
                        vulnerability.statement,
                        vulnerability.factAp,
                        includeStatement = when (vulnerability.vulnerabilityTriggerPosition) {
                            TaintSinkTracker.VulnerabilityTriggerPosition.BEFORE_INST -> false
                            TaintSinkTracker.VulnerabilityTriggerPosition.AFTER_INST -> true
                        }
                    )

                    for (trace in traces) {
                        builder.createSinkNode(trace)
                    }
                }

                val sourceToSinkTrace = builder.build()

                val entryPointToStart = resolveEntryPointToStartTrace(sourceToSinkTrace.startNodes)
                return Trace(entryPointToStart, sourceToSinkTrace)
            }
        }
    }

    private fun resolveEntryPointToStartTrace(startNodes: Set<SourceToSinkTraceNode>): EntryPointToStartTrace? {
        if (!params.resolveEntryPointToStartTrace) return null
        return EntryPointToStartTraceBuilder().build(startNodes)
    }

    private data class BuilderUnprocessedTrace(
        val trace: MethodTraceResolver.SummaryTrace,
        val kind: CallKind,
        val predecessor: InterProceduralCall? = null,
        val successor: InterProceduralCall? = null
    )

    private inner class InterProceduralTraceGraphBuilder {
        val fullNodes =
            hashMapOf<MethodEntryPoint, MutableMap<Pair<MethodTraceResolver.FullTrace, CallKind>, InterProceduralTraceNode>>()
        val summaryNodes =
            hashMapOf<MethodEntryPoint, MutableMap<Pair<MethodTraceResolver.SummaryTrace, CallKind>, List<InterProceduralTraceNode>>>()

        val sinkNodes = hashSetOf<InterProceduralTraceNode>()
        val rootNodes = hashSetOf<InterProceduralTraceNode>()
        val successors = hashMapOf<InterProceduralTraceNode, MutableSet<InterProceduralCall>>()

        val unprocessed = mutableListOf<BuilderUnprocessedTrace>()

        private var startToSourceTraceResolutionStat = 0
        private var startToSinkTraceResolutionStat = 0

        fun createSinkNode(trace: MethodTraceResolver.SummaryTrace) {
            val nodes = resolveNode(trace, CallKind.CallToSink)
            sinkNodes.addAll(nodes)
        }

        fun build(): SourceToSinkTrace {
            process()

            return SourceToSinkTrace(rootNodes, sinkNodes, successors)
        }

        private fun process() {
            while (unprocessed.isNotEmpty() && cancellation.isActive) {
                val event = unprocessed.removeLast()
                val resolvedNodes = resolveNode(event.trace, event.kind)

                for (resolved in resolvedNodes) {
                    event.predecessor?.let { predecessor ->
                        val predSucc = successors.getOrPut(predecessor.node, ::hashSetOf)
                        predSucc.add(
                            InterProceduralCall(predecessor.kind, predecessor.statement, predecessor.summary, resolved)
                        )
                    }

                    event.successor?.let { successor ->
                        val nodeSucc = successors.getOrPut(resolved, ::hashSetOf)
                        nodeSucc.add(successor)
                    }
                }
            }
        }

        private fun resolveNode(trace: MethodTraceResolver.SummaryTrace, kind: CallKind): List<InterProceduralTraceNode> {
            val traceNodes = summaryNodes.getOrPut(trace.method, ::hashMapOf)
            val cacheKey = trace to kind
            val currentNode = traceNodes[cacheKey]
            if (currentNode != null) return currentNode

            val fullTraces = withMethodRunner(trace.method) {
                resolveIntraProceduralFullTrace(trace.method, trace, cancellation)
            }

            val resultNodes = mutableListOf<InterProceduralTraceNode>()

            for (fullTrace in fullTraces) {
                when (val start = fullTrace.startEntry) {
                    is SourceStartEntry -> {
                        resultNodes += resolveNode(fullTrace, kind)
                    }

                    is MethodEntry -> {
                        check(kind == CallKind.CallToSink) { "Unexpected trace: $trace" }

                        val node = InterProceduralFullTraceNode(fullTrace)
                        resultNodes += node

                        val callerTraces = resolveMethodEntry(start)
                        for ((callerStatement, callerTrace) in callerTraces) {
                            if (params.startToSinkTraceResolutionLimit != null) {
                                if (startToSinkTraceResolutionStat++ > params.startToSinkTraceResolutionLimit) continue
                            }

                            unprocessed += BuilderUnprocessedTrace(
                                trace = callerTrace,
                                kind = CallKind.CallToSink,
                                successor = InterProceduralCall(kind, callerStatement, callerTrace, node)
                            )
                        }
                    }
                }
            }

            traceNodes[cacheKey] = resultNodes
            return resultNodes
        }

        private fun resolveNode(trace: MethodTraceResolver.FullTrace, kind: CallKind): InterProceduralTraceNode {
            val traceNodes = fullNodes.getOrPut(trace.method, ::hashMapOf)
            val cacheKey = trace to kind
            val currentNode = traceNodes[cacheKey]
            if (currentNode != null) return currentNode

            when (val start = trace.startEntry) {
                is MethodEntry -> {
                    TODO("Method full traces not used in inter-procedural graph builder (yet)")
                }

                is SourceStartEntry -> {
                    val node = InterProceduralFullTraceNode(trace)
                    traceNodes[cacheKey] = node

                    if (kind == CallKind.CallToSink) {
                        rootNodes.add(node)
                    }

                    val callSummary = start.sourcePrimaryAction as? TraceEntryAction.CallSourceSummary
                    if (callSummary != null) {
                        if (params.startToSourceTraceResolutionLimit != null) {
                            if (startToSourceTraceResolutionStat++ > params.startToSourceTraceResolutionLimit) {
                                return node
                            }
                        }

                        unprocessed += BuilderUnprocessedTrace(
                            trace = callSummary.summaryTrace,
                            kind = CallKind.CallToSource,
                            predecessor = InterProceduralCall(
                                CallKind.CallToSource,
                                start.statement,
                                callSummary.summaryTrace,
                                node
                            )
                        )
                    }

                    return node
                }
            }
        }

        private fun resolveMethodEntry(
            methodEntry: MethodEntry
        ): List<Pair<CommonInst, MethodTraceResolver.SummaryTrace>> {
            val callers = findMethodCallers(methodEntry.entryPoint)
            return callers.flatMap { caller ->
                withMethodRunner(caller.callerEp) {
                    resolveIntraProceduralTraceSummaryFromCall(caller.callerEp, caller.statement, methodEntry)
                }.map { caller.statement to it }
            }
        }
    }

    inner class EntryPointToStartTraceBuilder {
        private val entryPointNodes = hashSetOf<EntryPointTraceNode>()
        private val nodeSuccessors = hashMapOf<TraceNode, MutableSet<TraceNode>>()

        fun build(startNodes: Set<SourceToSinkTraceNode>): EntryPointToStartTrace {
            val unprocessedMethods = mutableListOf<Pair<MethodEntryPoint, TraceNode>>()
            startNodes.mapTo(unprocessedMethods) { it.methodEntryPoint to it }

            val visitedEp = hashSetOf<Pair<MethodEntryPoint, TraceNode>>()
            while (unprocessedMethods.isNotEmpty() && cancellation.isActive) {
                val methodCall = unprocessedMethods.removeLast()
                if (!visitedEp.add(methodCall)) continue

                val (methodEp, methodCallNode) = methodCall
                if (methodEp.method in entryPointMethods) {
                    val epNode = EntryPointTraceNode(methodEp.method)
                    entryPointNodes.add(epNode)
                    nodeSuccessors.getOrPut(epNode, ::hashSetOf).add(methodCallNode)
                }

                val methodCallers = findMethodCallers(methodEp)
                for (caller in methodCallers) {
                    val callNode = CallTraceNode(caller.statement, caller.callerEp)
                    nodeSuccessors.getOrPut(callNode, ::hashSetOf).add(methodCallNode)
                    unprocessedMethods += (caller.callerEp to callNode)
                }
            }

            return EntryPointToStartTrace(entryPointNodes, nodeSuccessors)
        }
    }

    private inline fun <T> withMethodRunner(
        methodEntryPoint: MethodEntryPoint,
        body: TaintAnalysisUnitRunner.() -> T
    ): T {
        val unit = manager.unitResolver.resolve(methodEntryPoint.method)
        val runner = manager.findUnitRunner(unit) ?: error("No runner for unit: $unit")
        return runner.body()
    }

    private fun findMethodCallers(methodEntryPoint: MethodEntryPoint): Set<MethodEntryPointCaller> {
        val result = hashSetOf<MethodEntryPointCaller>()

        withMethodRunner(methodEntryPoint) {
            methodCallers(methodEntryPoint, collectZeroCallsOnly = true, result)
        }

        val callers = manager.methodCallers(methodEntryPoint.method)
        for (callerUnit in callers) {
            val runner = manager.findUnitRunner(callerUnit) ?: error("No runner for unit: $callerUnit")
            runner.methodCallers(methodEntryPoint, collectZeroCallsOnly = true, result)
        }

        return result
    }
}

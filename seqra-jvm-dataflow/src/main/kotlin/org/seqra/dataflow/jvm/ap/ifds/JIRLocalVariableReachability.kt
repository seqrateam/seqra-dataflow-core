package org.seqra.dataflow.jvm.ap.ifds

import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.cfg.JIRAssignInst
import org.seqra.ir.api.jvm.cfg.JIRCatchInst
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.ir.api.jvm.cfg.JIRLocalVar
import org.seqra.ir.api.jvm.cfg.locals
import org.seqra.jvm.graph.JApplicationGraph
import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.Companion.instructionStorageIdx
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.Companion.instructionStorageSize
import org.seqra.dataflow.util.containsAll
import org.seqra.dataflow.util.copy
import java.util.BitSet

class JIRLocalVariableReachability(
    private val method: JIRMethod,
    private val graph: JApplicationGraph,
    private val languageManager: JIRLanguageManager
) {
    private val maxInstIdx = method.instList.maxOf { it.location.index }

    private val reachabilityInfo by lazy { computeReachability() }

    fun isReachable(base: AccessPathBase, statement: CommonInst): Boolean {
        if (base !is AccessPathBase.LocalVar) return true
        val storageIdx = instructionStorageIdx(statement, languageManager)
        val storage = reachabilityInfo[storageIdx] ?: return false
        return storage.get(base.idx)
    }

    private fun computeReachability(): Array<BitSet?> {
        val statementReachability = arrayOfNulls<BitSet?>(instructionStorageSize(maxInstIdx))
        val unprocessed = graph.exitPoints(method).mapTo(mutableListOf()) { it to BitSet() }

        while (unprocessed.isNotEmpty()) {
            val (statement, prevReachability) = unprocessed.removeLast()
            val storageIdx = instructionStorageIdx(statement, languageManager)
            val currentReachability = statementReachability[storageIdx]

            // no new reachability info
            if (currentReachability != null && currentReachability.containsAll(prevReachability)) {
                continue
            }

            val reachableLocalsAtStatement = BitSet()
            reachableLocalsAtStatement.or(prevReachability)
            if (currentReachability != null) {
                reachableLocalsAtStatement.or(currentReachability)
            }

            statement.locals.forEach {
                if (it is JIRLocalVar) {
                    reachableLocalsAtStatement.set(it.index)
                }
            }

            statementReachability[storageIdx] = reachableLocalsAtStatement

            val removedVar = statement.assignedLocalVar()
            val nextReachable = if (removedVar != null) {
                reachableLocalsAtStatement.copy().also { it.clear(removedVar.index) }
            } else {
                reachableLocalsAtStatement
            }

            graph.predecessors(statement).forEach { unprocessed.add(it to nextReachable) }
        }

        return statementReachability
    }

    private fun JIRInst.assignedLocalVar(): JIRLocalVar? = when (this) {
        is JIRAssignInst -> lhv as? JIRLocalVar
        is JIRCatchInst -> throwable as? JIRLocalVar
        else -> null
    }
}

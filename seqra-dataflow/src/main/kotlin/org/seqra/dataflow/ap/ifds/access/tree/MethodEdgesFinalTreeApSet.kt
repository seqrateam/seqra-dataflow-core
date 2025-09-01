package org.seqra.dataflow.ap.ifds.access.tree

import org.seqra.dataflow.ap.ifds.LanguageManager
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.Companion.instructionStorageIdx
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.Companion.instructionStorageSize
import org.seqra.dataflow.ap.ifds.access.common.CommonZ2FSet
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.dataflow.ap.ifds.access.tree.AccessTree.AccessNode as AccessTreeNode

class MethodEdgesFinalTreeApSet(
    methodInitialStatement: CommonInst,
    private val maxInstIdx: Int,
    private val languageManager: LanguageManager,
) : CommonZ2FSet<AccessTreeNode>(methodInitialStatement), TreeFinalApAccess {
    override fun createApStorage(): ApStorage<AccessTree.AccessNode> =
        ZeroInitialFactEdges(maxInstIdx, languageManager)

    private class ZeroInitialFactEdges(
        maxInstIdx: Int,
        private val languageManager: LanguageManager
    ): ApStorage<AccessTreeNode> {
        private val edges = arrayOfNulls<AccessTreeNode?>(instructionStorageSize(maxInstIdx))

        override fun addEdge(statement: CommonInst, accessPath: AccessTreeNode): AccessTreeNode? {
            val factSetIdx = instructionStorageIdx(statement, languageManager)
            val factSet = edges[factSetIdx]

            if (factSet == null) {
                edges[factSetIdx] = accessPath
                return accessPath
            }

            val mergedFacts = factSet.mergeAdd(accessPath)
            if (mergedFacts === factSet) {
                return null
            }

            edges[factSetIdx] = mergedFacts
            return mergedFacts
        }

        override fun collectApAtStatement(statement: CommonInst, dst: MutableList<AccessTree.AccessNode>) {
            dst += edges[instructionStorageIdx(statement, languageManager)] ?: return
        }
    }
}

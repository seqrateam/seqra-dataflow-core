package org.seqra.dataflow.ap.ifds.access.cactus

import org.seqra.dataflow.ap.ifds.LanguageManager
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.Companion.instructionStorageIdx
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.Companion.instructionStorageSize
import org.seqra.dataflow.ap.ifds.access.common.CommonZ2FSet
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.dataflow.ap.ifds.access.cactus.AccessCactus.AccessNode as AccessCactusNode

class MethodEdgesFinalCactusApSet(
    methodInitialStatement: CommonInst,
    private val maxInstIdx: Int,
    private val languageManager: LanguageManager,
) : CommonZ2FSet<AccessCactusNode>(methodInitialStatement), CactusFinalApAccess {
    override fun createApStorage(): ApStorage<AccessCactus.AccessNode> =
        ZeroInitialFactEdges(maxInstIdx, languageManager)

    private class ZeroInitialFactEdges(
        maxInstIdx: Int,
        private val languageManager: LanguageManager,
    ): ApStorage<AccessCactusNode> {
        private val edges = arrayOfNulls<AccessCactusNode?>(instructionStorageSize(maxInstIdx))

        override fun addEdge(statement: CommonInst, accessPath: AccessCactusNode): AccessCactusNode? {
            val factSetIdx = instructionStorageIdx(statement, languageManager)
            val factSet = edges[factSetIdx]

            if (factSet == null) {
                edges[factSetIdx] = accessPath
                return accessPath
            }

            val mergedFacts = factSet.mergeAdd(accessPath)
            if (mergedFacts == factSet) {
                return null
            }

            edges[factSetIdx] = mergedFacts
            return mergedFacts
        }

        override fun collectApAtStatement(statement: CommonInst, dst: MutableList<AccessCactus.AccessNode>) {
            edges[instructionStorageIdx(statement, languageManager)]?.let { dst.add(it) }
        }
    }
}

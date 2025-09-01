package org.seqra.dataflow.ap.ifds.access.tree

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.LanguageManager
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges
import org.seqra.dataflow.ap.ifds.access.common.CommonF2FSet
import org.seqra.dataflow.util.collectToListWithPostProcess
import org.seqra.ir.api.common.cfg.CommonInst

class MethodEdgesInitialToFinalTreeApSet(
    methodInitialStatement: CommonInst,
    private val maxInstIdx: Int,
    private val languageManager: LanguageManager,
) : CommonF2FSet<AccessPath.AccessNode?, AccessTree.AccessNode>(methodInitialStatement),
    TreeInitialApAccess, TreeFinalApAccess {

    override fun createApStorage(): ApStorage<AccessPath.AccessNode?, AccessTree.AccessNode> =
        TaintedFactAccessEdgeStorage()

    private inner class TaintedFactAccessEdgeStorage : ApStorage<AccessPath.AccessNode?, AccessTree.AccessNode> {
        private val sameInitialAccessEdges =
            Object2ObjectOpenHashMap<AccessPath.AccessNode?, EdgeNonUniverseExclusionMergingStorage>()

        override fun add(
            statement: CommonInst,
            initial: AccessPath.AccessNode?,
            final: AccessWithExclusion<AccessTree.AccessNode>,
        ): AccessWithExclusion<AccessTree.AccessNode>? {
            val storage = sameInitialAccessEdges.getOrPut(initial) {
                EdgeNonUniverseExclusionMergingStorage(maxInstIdx, languageManager)
            }

            return storage.add(statement, final)
        }

        override fun filter(
            dst: MutableList<Pair<AccessPath.AccessNode?, AccessWithExclusion<AccessTree.AccessNode>>>,
            statement: CommonInst,
            finalPattern: AccessPath.AccessNode?,
        ) {
            sameInitialAccessEdges.forEach { (initial, storage) ->
                collectToListWithPostProcess(
                    dst,
                    { storage.allApAtStatement(it, statement) },
                    { initial to it }
                )
            }
        }

        override fun filter(
            dst: MutableList<AccessWithExclusion<AccessTree.AccessNode>>,
            statement: CommonInst,
            initial: AccessPath.AccessNode?,
            finalPattern: AccessPath.AccessNode?,
        ) {
            val storage = sameInitialAccessEdges[initial] ?: return
            storage.allApAtStatement(dst, statement)
        }
    }

    private class EdgeNonUniverseExclusionMergingStorage(
        maxInstIdx: Int,
        private val languageManager: LanguageManager
    ) {
        private val exclusions = arrayOfNulls<ExclusionSet>(MethodAnalyzerEdges.instructionStorageSize(maxInstIdx))
        private val edges = arrayOfNulls<AccessTree.AccessNode>(MethodAnalyzerEdges.instructionStorageSize(maxInstIdx))

        fun add(
            statement: CommonInst,
            accessWithExclusion: AccessWithExclusion<AccessTree.AccessNode>
        ): AccessWithExclusion<AccessTree.AccessNode>? {
            val edgeSetIdx = MethodAnalyzerEdges.instructionStorageIdx(statement, languageManager)
            val currentExclusion = exclusions[edgeSetIdx]

            if (currentExclusion == null) {
                exclusions[edgeSetIdx] = accessWithExclusion.exclusion
                edges[edgeSetIdx] = accessWithExclusion.access
                return accessWithExclusion
            }

            val mergedExclusion = currentExclusion.union(accessWithExclusion.exclusion)
            exclusions[edgeSetIdx] = mergedExclusion

            val currentAccess = edges[edgeSetIdx]!!
            val mergedAccess = currentAccess.mergeAdd(accessWithExclusion.access)
            if (mergedAccess === currentAccess) return null

            edges[edgeSetIdx] = mergedAccess
            return AccessWithExclusion(mergedAccess, mergedExclusion)
        }

        fun allApAtStatement(dst: MutableList<AccessWithExclusion<AccessTree.AccessNode>>, statement: CommonInst) {
            val edgeSetIdx = MethodAnalyzerEdges.instructionStorageIdx(statement, languageManager)
            val currentExclusion = exclusions[edgeSetIdx] ?: return
            val access = edges[edgeSetIdx] ?: return
            dst += AccessWithExclusion(access, currentExclusion)
        }
    }
}

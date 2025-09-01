package org.seqra.dataflow.ap.ifds.access.common

import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.LanguageManager
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.Companion.instructionStorageSize
import org.seqra.dataflow.ap.ifds.MethodAnalyzerEdges.EdgeStorage
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.access.MethodEdgesNDInitialToFinalApSet
import org.seqra.dataflow.util.collectToListWithPostProcess
import org.seqra.ir.api.common.cfg.CommonInst

abstract class CommonNDF2FSet<IAP, FAP>(
    private val initialStatement: CommonInst,
    private val languageManager: LanguageManager,
    private val maxInstIdx: Int,
): MethodEdgesNDInitialToFinalApSet, InitialApAccess<IAP>, FinalApAccess<FAP> {

    interface ApStorage<IAP, FAP> {
        fun add(initial: Set<InitialFactAp>, final: FAP): FAP?
        fun filter(dst: MutableList<Pair<Set<InitialFactAp>, FAP>>, finalPattern: IAP)
        fun filter(dst: MutableList<FAP>, initial: Set<InitialFactAp>, finalPattern: IAP)
    }

    abstract fun createApStorage(): ApStorage<IAP, FAP>

    private val storage = ExitFactBaseStorage()

    override fun add(
        statement: CommonInst,
        initial: Set<InitialFactAp>,
        finalAp: FinalFactAp
    ): Pair<Set<InitialFactAp>, FinalFactAp>? {
        val edgeStorage = storage.getOrCreate(finalAp.base)
        val addedFinal = edgeStorage.add(statement, initial, getFinalAccess(finalAp)) ?: return null
        val newExitAp = createFinal(finalAp.base, addedFinal, ExclusionSet.Universe)
        return initial to newExitAp
    }

    override fun collectApAtStatement(
        collection: MutableList<Pair<Set<InitialFactAp>, FinalFactAp>>,
        statement: CommonInst,
        finalFactPattern: InitialFactAp
    ) {
        val finalFactBase = finalFactPattern.base
        val finalStorage = storage.find(finalFactBase) ?: return

        collectToListWithPostProcess(
            collection,
            { finalStorage.collectApAtStatement(it, statement, getInitialAccess(finalFactPattern)) },
            {
                val finalAp = createFinal(finalFactBase, it.second, ExclusionSet.Universe)
                it.first to finalAp
            }
        )
    }

    override fun collectApAtStatement(
        collection: MutableList<FinalFactAp>,
        statement: CommonInst,
        initial: Set<InitialFactAp>,
        finalFactPattern: InitialFactAp
    ) {
        val finalFactBase = finalFactPattern.base
        val finalStorage = storage.find(finalFactBase) ?: return

        collectToListWithPostProcess(
            collection,
            { finalStorage.collectApAtStatement(it, statement, initial, getInitialAccess(finalFactPattern)) },
            { createFinal(finalFactBase, it, ExclusionSet.Universe) }
        )
    }

    private inner class ExitFactBaseStorage : EdgeStorage<StatementStorage>(initialStatement) {
        override fun createStorage() = StatementStorage()
    }

    private inner class StatementStorage {
        private val statementStorage = arrayOfNulls<ApStorage<IAP, FAP>>(instructionStorageSize(maxInstIdx))

        fun add(
            statement: CommonInst,
            initial: Set<InitialFactAp>,
            finalAp: FAP,
        ): FAP? {
            val idx = languageManager.getInstIndex(statement)
            val factStorage = statementStorage[idx]
                ?: createApStorage().also { statementStorage[idx] = it }
            return factStorage.add(initial, finalAp)
        }

        fun collectApAtStatement(
            collection: MutableList<Pair<Set<InitialFactAp>, FAP>>,
            statement: CommonInst,
            finalFactPattern: IAP,
        ) {
            val factStorage = statementStorage[languageManager.getInstIndex(statement)] ?: return
            factStorage.filter(collection, finalFactPattern)
        }

        fun collectApAtStatement(
            collection: MutableList<FAP>,
            statement: CommonInst,
            initial: Set<InitialFactAp>,
            finalFactPattern: IAP,
        ) {
            val factStorage = statementStorage[languageManager.getInstIndex(statement)] ?: return
            factStorage.filter(collection, initial, finalFactPattern)
        }
    }
}

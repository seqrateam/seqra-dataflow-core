package org.seqra.dataflow.ap.ifds.access.common.ndf2f

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.SummaryFactStorage
import org.seqra.dataflow.util.ConcurrentReadSafeObject2IntMap
import org.seqra.dataflow.util.object2IntMap
import org.seqra.ir.api.common.cfg.CommonInst
import java.util.BitSet

class DefaultApInterner<IAP>(methodEntryPoint: CommonInst) {
    private var size = 0
    private val storage = BasedStorage<IAP>(methodEntryPoint)

    fun getOrCreateIndex(
        base: AccessPathBase,
        node: IAP,
        onNewIdx: (Int) -> Unit,
    ): Int {
        val interner = storage.getOrCreate(base)
        return interner.getOrCreate(node, size) {
            size++
            onNewIdx(it)
        }
    }

    fun findBaseIndices(base: AccessPathBase): BitSet? = storage.find(base)?.allIndices

    private class BasedStorage<IAP>(methodEntryPoint: CommonInst) :
        SummaryFactStorage<ApInterner<IAP>>(methodEntryPoint) {
        override fun createStorage(): ApInterner<IAP> = ApInterner()
    }

    private class ApInterner<IAP> {
        val allIndices = BitSet()
        val apIdx = object2IntMap<IAP>()

        inline fun getOrCreate(ap: IAP, nextIdx: Int, onNewIdx: (Int) -> Unit): Int {
            val currentIndex = apIdx.putIfAbsent(ap, nextIdx)
            if (currentIndex != ConcurrentReadSafeObject2IntMap.NO_VALUE) return currentIndex
            allIndices.set(nextIdx)
            onNewIdx(nextIdx)
            return nextIdx
        }
    }
}

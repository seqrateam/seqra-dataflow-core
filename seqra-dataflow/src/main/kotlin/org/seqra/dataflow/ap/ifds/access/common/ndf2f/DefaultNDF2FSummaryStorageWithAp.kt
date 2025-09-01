package org.seqra.dataflow.ap.ifds.access.common.ndf2f

import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.access.common.InitialApAccess
import org.seqra.dataflow.util.PersistentBitSet.Companion.emptyPersistentBitSet
import org.seqra.ir.api.common.cfg.CommonInst
import java.util.BitSet

abstract class DefaultNDF2FSummaryStorageWithAp<IAP, FAP : Any>(
    methodEntryPoint: CommonInst,
) : DefaultNDF2FSummaryStorage<IAP, FAP>(), InitialApAccess<IAP> {
    private val apIdx = DefaultApInterner<IAP>(methodEntryPoint)
    private val ap = arrayListOf<InitialFactAp>()

    override fun initialApIdx(ap: InitialFactAp): Int = apIdx.getOrCreateIndex(ap.base, getInitialAccess(ap)) {
        this.ap.add(ap)
    }

    override fun getInitialApByIdx(idx: Int): InitialFactAp = ap[idx]

    override fun relevantInitialAp(summaryInitialFactPattern: FinalFactAp): BitSet =
        apIdx.findBaseIndices(summaryInitialFactPattern.base) ?: emptyPersistentBitSet()
}

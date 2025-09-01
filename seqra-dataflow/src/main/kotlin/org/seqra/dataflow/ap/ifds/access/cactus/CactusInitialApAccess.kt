package org.seqra.dataflow.ap.ifds.access.cactus

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.access.common.InitialApAccess

interface CactusInitialApAccess: InitialApAccess<AccessPathWithCycles.AccessNode?> {
    override fun getInitialAccess(factAp: InitialFactAp): AccessPathWithCycles.AccessNode? =
        (factAp as AccessPathWithCycles).access

    override fun createInitial(base: AccessPathBase, ap: AccessPathWithCycles.AccessNode?, ex: ExclusionSet): InitialFactAp =
        AccessPathWithCycles(base, ap, ex)
}

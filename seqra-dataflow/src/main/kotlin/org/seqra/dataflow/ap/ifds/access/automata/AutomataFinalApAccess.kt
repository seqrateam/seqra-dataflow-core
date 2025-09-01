package org.seqra.dataflow.ap.ifds.access.automata

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.common.FinalApAccess

interface AutomataFinalApAccess : FinalApAccess<AccessGraph> {
    override fun getFinalAccess(factAp: FinalFactAp): AccessGraph = (factAp as AccessGraphFinalFactAp).access
    override fun createFinal(base: AccessPathBase, ap: AccessGraph, ex: ExclusionSet): FinalFactAp = AccessGraphFinalFactAp(base, ap, ex)
}

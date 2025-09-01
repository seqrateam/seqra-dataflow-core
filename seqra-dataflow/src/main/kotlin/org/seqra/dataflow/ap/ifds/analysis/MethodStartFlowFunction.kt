package org.seqra.dataflow.ap.ifds.analysis

import org.seqra.dataflow.ap.ifds.access.FinalFactAp

interface MethodStartFlowFunction {
    sealed interface StartFact {
        data object Zero : StartFact
        data class Fact(val fact: FinalFactAp) : StartFact
    }

    fun propagateZero(): List<StartFact>

    fun propagateFact(fact: FinalFactAp): List<StartFact.Fact>
}

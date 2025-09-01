package org.seqra.dataflow.ap.ifds.trace

import org.seqra.dataflow.ap.ifds.access.InitialFactAp

interface MethodStartPrecondition {
    fun factPrecondition(fact: InitialFactAp): List<TaintRulePrecondition.Source>
}

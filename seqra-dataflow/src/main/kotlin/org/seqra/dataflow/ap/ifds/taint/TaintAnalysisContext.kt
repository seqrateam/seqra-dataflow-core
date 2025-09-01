package org.seqra.dataflow.ap.ifds.taint

import org.seqra.dataflow.configuration.CommonTaintRulesProvider

data class TaintAnalysisContext(
    val taintConfig: CommonTaintRulesProvider,
    val taintSinkTracker: TaintSinkTracker,
)

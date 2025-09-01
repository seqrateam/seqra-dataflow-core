package org.seqra.dataflow.ap.ifds.analysis

import org.seqra.dataflow.ap.ifds.MethodEntryPoint

interface MethodAnalysisContext {
    val methodEntryPoint: MethodEntryPoint

    // todo: remove, required for trace generation
    val methodCallFactMapper: MethodCallFactMapper
}

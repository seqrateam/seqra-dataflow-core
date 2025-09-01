package org.seqra.dataflow.ap.ifds.trace

class TraceResolverCancellation {
    @Volatile
    var isActive: Boolean = true

    fun cancel() {
        isActive = false
    }
}

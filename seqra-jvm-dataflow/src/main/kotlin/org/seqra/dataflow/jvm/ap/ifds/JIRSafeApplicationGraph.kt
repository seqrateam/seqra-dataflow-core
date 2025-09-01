package org.seqra.dataflow.jvm.ap.ifds

import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.jvm.graph.JApplicationGraph

class JIRSafeApplicationGraph(
    private val graph: JApplicationGraph
) : JApplicationGraph by graph {
    override fun entryPoints(method: JIRMethod): Sequence<JIRInst> = try {
        graph.entryPoints(method)
    } catch (e: Throwable) {
        // we couldn't find instructions list
        // TODO: maybe fix flowGraph()
        emptySequence()
    }

    override fun exitPoints(method: JIRMethod): Sequence<JIRInst> = try {
        graph.exitPoints(method)
    } catch (e: Throwable) {
        // we couldn't find instructions list
        // TODO: maybe fix flowGraph()
        emptySequence()
    }
}

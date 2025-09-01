package org.seqra.dataflow.jvm.flow

import org.seqra.ir.api.jvm.cfg.JIRBytecodeGraph

abstract class ForwardFlowAnalysis<NODE, T>(graph: JIRBytecodeGraph<NODE>) : FlowAnalysisImpl<NODE, T>(graph) {

    override val isForward = true

    override fun run() {
        runAnalysis(FlowAnalysisDirection.FORWARD, ins, outs)
    }
}

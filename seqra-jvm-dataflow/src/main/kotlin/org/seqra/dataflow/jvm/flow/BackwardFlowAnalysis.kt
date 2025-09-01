package org.seqra.dataflow.jvm.flow

import org.seqra.ir.api.jvm.cfg.JIRBytecodeGraph

abstract class BackwardFlowAnalysis<NODE, T>(graph: JIRBytecodeGraph<NODE>) : FlowAnalysisImpl<NODE, T>(graph) {

    override val isForward: Boolean = false

    override fun run() {
        runAnalysis(FlowAnalysisDirection.BACKWARD, outs, ins)
    }
}

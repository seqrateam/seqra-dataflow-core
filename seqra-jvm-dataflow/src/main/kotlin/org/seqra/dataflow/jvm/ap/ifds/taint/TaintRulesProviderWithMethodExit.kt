package org.seqra.dataflow.jvm.ap.ifds.taint

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.dataflow.configuration.jvm.TaintMethodExitSink

class TaintRulesProviderWithMethodExit(
    private val entryPoints: Set<JIRMethod>,
    private val base: TaintRulesProvider
) : TaintRulesProvider by base {
    override fun sinkRulesForMethodExit(method: CommonMethod, statement: CommonInst): Iterable<TaintMethodExitSink> {
        val rules = base.sinkRulesForMethodExit(method, statement)
        if (method !in entryPoints) return rules

        val analysisEnd = base.sinkRulesForAnalysisEnd(method, statement)
        return rules + analysisEnd
    }
}

fun TaintRulesProvider.applyAnalysisEndSinksForEntryPoints(entryPoints: Set<JIRMethod>): TaintRulesProvider =
    TaintRulesProviderWithMethodExit(entryPoints, this)

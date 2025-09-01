package org.seqra.dataflow.jvm.ap.ifds.taint

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.jvm.JIRField
import org.seqra.dataflow.configuration.CommonTaintRulesProvider
import org.seqra.dataflow.configuration.jvm.TaintCleaner
import org.seqra.dataflow.configuration.jvm.TaintEntryPointSource
import org.seqra.dataflow.configuration.jvm.TaintMethodEntrySink
import org.seqra.dataflow.configuration.jvm.TaintMethodExitSink
import org.seqra.dataflow.configuration.jvm.TaintMethodSink
import org.seqra.dataflow.configuration.jvm.TaintMethodSource
import org.seqra.dataflow.configuration.jvm.TaintPassThrough
import org.seqra.dataflow.configuration.jvm.TaintStaticFieldSource

interface TaintRulesProvider : CommonTaintRulesProvider {
    fun entryPointRulesForMethod(method: CommonMethod): Iterable<TaintEntryPointSource>
    fun sourceRulesForMethod(method: CommonMethod, statement: CommonInst): Iterable<TaintMethodSource>
    fun sinkRulesForMethod(method: CommonMethod, statement: CommonInst): Iterable<TaintMethodSink>
    fun sinkRulesForMethodEntry(method: CommonMethod): Iterable<TaintMethodEntrySink>
    fun sinkRulesForMethodExit(method: CommonMethod, statement: CommonInst): Iterable<TaintMethodExitSink>
    fun sinkRulesForAnalysisEnd(method: CommonMethod, statement: CommonInst): Iterable<TaintMethodExitSink>
    fun passTroughRulesForMethod(method: CommonMethod, statement: CommonInst): Iterable<TaintPassThrough>
    fun cleanerRulesForMethod(method: CommonMethod, statement: CommonInst): Iterable<TaintCleaner>
    fun sourceRulesForStaticField(field: JIRField, statement: CommonInst): Iterable<TaintStaticFieldSource>
}

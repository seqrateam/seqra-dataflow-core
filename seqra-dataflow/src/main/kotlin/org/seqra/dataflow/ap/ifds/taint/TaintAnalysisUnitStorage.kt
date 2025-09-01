package org.seqra.dataflow.ap.ifds.taint

import org.seqra.dataflow.ap.ifds.LanguageManager
import org.seqra.dataflow.ap.ifds.MethodSummariesUnitStorage
import org.seqra.dataflow.ap.ifds.access.ApManager
import java.util.concurrent.ConcurrentLinkedQueue

class TaintAnalysisUnitStorage(apManager: ApManager, languageManager: LanguageManager)
    : MethodSummariesUnitStorage(apManager, languageManager)
{
    private val vulnerabilities = ConcurrentLinkedQueue<TaintSinkTracker.TaintVulnerability>()

    fun addVulnerability(vulnerability: TaintSinkTracker.TaintVulnerability) {
        vulnerabilities.add(vulnerability)
    }

    fun collectVulnerabilities(collector: MutableList<TaintSinkTracker.TaintVulnerability>) {
        collector.addAll(vulnerabilities)
    }
}

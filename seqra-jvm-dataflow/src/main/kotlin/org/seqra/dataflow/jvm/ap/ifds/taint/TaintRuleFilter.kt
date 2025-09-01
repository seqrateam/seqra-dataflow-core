package org.seqra.dataflow.jvm.ap.ifds.taint

import org.seqra.dataflow.configuration.jvm.TaintConfigurationItem


fun interface TaintRuleFilter {
    fun ruleEnabled(rule: TaintConfigurationItem): Boolean
}

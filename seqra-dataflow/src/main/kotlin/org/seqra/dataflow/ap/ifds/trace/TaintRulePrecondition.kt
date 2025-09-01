package org.seqra.dataflow.ap.ifds.trace

import org.seqra.dataflow.configuration.CommonTaintAction
import org.seqra.dataflow.configuration.CommonTaintAssignAction
import org.seqra.dataflow.configuration.CommonTaintConfigurationItem
import org.seqra.dataflow.configuration.CommonTaintConfigurationSource

sealed interface TaintRulePrecondition {
    interface PassRuleCondition

    data class Source(
        val rule: CommonTaintConfigurationSource,
        val action: Set<CommonTaintAssignAction>,
    ) : TaintRulePrecondition

    data class Pass(
        val rule: CommonTaintConfigurationItem,
        val action: Set<CommonTaintAction>,
        val condition: PassRuleCondition,
    ) : TaintRulePrecondition
}

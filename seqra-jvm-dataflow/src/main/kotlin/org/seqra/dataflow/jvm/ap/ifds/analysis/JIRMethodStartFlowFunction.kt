package org.seqra.dataflow.jvm.ap.ifds.analysis

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.EmptyMethodContext
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.MethodEntryPoint
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.analysis.MethodStartFlowFunction
import org.seqra.dataflow.ap.ifds.analysis.MethodStartFlowFunction.StartFact
import org.seqra.dataflow.jvm.ap.ifds.CalleePositionToJIRValueResolver
import org.seqra.dataflow.jvm.ap.ifds.JIRMarkAwareConditionRewriter
import org.seqra.dataflow.jvm.ap.ifds.JIRSimpleFactAwareConditionEvaluator
import org.seqra.dataflow.jvm.ap.ifds.TaintConfigUtils.applyEntryPointConfig
import org.seqra.dataflow.jvm.ap.ifds.jIRDowncast
import org.seqra.dataflow.jvm.ap.ifds.taint.TaintRulesProvider
import org.seqra.dataflow.jvm.ap.ifds.taint.TaintSourceActionEvaluator
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.ext.toType
import org.seqra.util.onSome

class JIRMethodStartFlowFunction(
    private val apManager: ApManager,
    private val context: JIRMethodAnalysisContext,
) : MethodStartFlowFunction {
    override fun propagateZero(): List<StartFact> {
        val result = mutableListOf<StartFact>()
        result.add(StartFact.Zero)

        applySinkRules()

        val method = context.methodEntryPoint.method as JIRMethod
        val valueResolver = CalleePositionToJIRValueResolver(method)
        val conditionRewriter = JIRMarkAwareConditionRewriter(
            valueResolver, context.factTypeChecker
        )

        val conditionEvaluator = JIRSimpleFactAwareConditionEvaluator(conditionRewriter, evaluator = null)

        val sourceEvaluator = TaintSourceActionEvaluator(
            apManager,
            exclusion = ExclusionSet.Universe,
            context.factTypeChecker,
            returnValueType = null
        )

        applyEntryPointConfig(
            context.taint.taintConfig as TaintRulesProvider,
            method, conditionEvaluator, sourceEvaluator
        ).onSome { facts ->
            facts.mapTo(result) { StartFact.Fact(it) }
        }

        return result
    }

    override fun propagateFact(fact: FinalFactAp): List<StartFact.Fact> {
        val checkedFact = checkInitialFactTypes(context.methodEntryPoint, fact) ?: return emptyList()
        return listOf(StartFact.Fact(checkedFact))
    }

    private fun checkInitialFactTypes(methodEntryPoint: MethodEntryPoint, factAp: FinalFactAp): FinalFactAp? {
        if (factAp.base !is AccessPathBase.This) return factAp

        val thisClass = when (val context = methodEntryPoint.context) {
            EmptyMethodContext -> {
                val method = methodEntryPoint.method
                jIRDowncast<JIRMethod>(method)
                method.enclosingClass
            }
            is org.seqra.dataflow.jvm.ap.ifds.JIRInstanceTypeMethodContext -> context.type
            else -> error("Unexpected value for context: $context")
        }

        val thisType = thisClass.toType()
        return context.factTypeChecker.filterFactByLocalType(thisType, factAp)
    }

    private fun applySinkRules() {
        val config = context.taint.taintConfig as TaintRulesProvider
        val method = context.methodEntryPoint.method
        val statement = context.methodEntryPoint.statement

        val sinkRules = config.sinkRulesForMethodEntry(method).toList()
        if (sinkRules.isEmpty()) return

        val valueResolver = CalleePositionToJIRValueResolver(method as JIRMethod)
        val conditionRewriter = JIRMarkAwareConditionRewriter(
            valueResolver,
            context.factTypeChecker
        )

        val conditionEvaluator = JIRSimpleFactAwareConditionEvaluator(conditionRewriter, evaluator = null)

        for (rule in sinkRules) {
            if (!conditionEvaluator.eval(rule.condition)) {
                continue
            }

            context.taint.taintSinkTracker.addUnconditionalVulnerability(
                context.methodEntryPoint, statement, rule
            )
        }
    }
}

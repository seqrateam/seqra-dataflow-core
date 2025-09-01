package org.seqra.dataflow.jvm.ap.ifds.trace

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.Accessor
import org.seqra.dataflow.ap.ifds.ElementAccessor
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.trace.MethodSequentPrecondition
import org.seqra.dataflow.ap.ifds.trace.MethodSequentPrecondition.PreconditionFactsForInitialFact
import org.seqra.dataflow.ap.ifds.trace.MethodSequentPrecondition.SequentPrecondition
import org.seqra.dataflow.ap.ifds.trace.MethodSequentPrecondition.SequentPreconditionFacts
import org.seqra.dataflow.ap.ifds.trace.TaintRulePrecondition
import org.seqra.dataflow.configuration.jvm.ConstantTrue
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.accessPathBase
import org.seqra.dataflow.jvm.ap.ifds.analysis.JIRMethodAnalysisContext
import org.seqra.dataflow.jvm.ap.ifds.analysis.forEachPossibleAliasAtStatement
import org.seqra.dataflow.jvm.ap.ifds.taint.InitialFactReader
import org.seqra.dataflow.jvm.ap.ifds.taint.TaintRulesProvider
import org.seqra.dataflow.jvm.ap.ifds.taint.TaintSourceActionPreconditionEvaluator
import org.seqra.ir.api.jvm.cfg.JIRArrayAccess
import org.seqra.ir.api.jvm.cfg.JIRAssignInst
import org.seqra.ir.api.jvm.cfg.JIRCastExpr
import org.seqra.ir.api.jvm.cfg.JIRExpr
import org.seqra.ir.api.jvm.cfg.JIRFieldRef
import org.seqra.ir.api.jvm.cfg.JIRImmediate
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.ir.api.jvm.cfg.JIRReturnInst
import org.seqra.ir.api.jvm.cfg.JIRThrowInst
import org.seqra.ir.api.jvm.cfg.JIRValue
import org.seqra.util.maybeFlatMap

class JIRMethodSequentPrecondition(
    private val apManager: ApManager,
    private val currentInst: JIRInst,
    private val analysisContext: JIRMethodAnalysisContext
) : MethodSequentPrecondition {

    override fun factPrecondition(
        fact: InitialFactAp
    ): SequentPrecondition {
        val results = mutableListOf<SequentPreconditionFacts>()

        preconditionForFact(fact)?.let {
            results += PreconditionFactsForInitialFact(fact, it)
        }

        results.unconditionalSourcesPrecondition(fact)

        analysisContext.aliasAnalysis?.forEachPossibleAliasAtStatement(currentInst, fact) { aliasedFact ->
            preconditionForFact(aliasedFact)?.let {
                results += PreconditionFactsForInitialFact(aliasedFact, it)
            }

            results.unconditionalSourcesPrecondition(aliasedFact)
        }

        return if (results.isEmpty()) {
            SequentPrecondition.Unchanged
        } else {
            SequentPrecondition.Facts(results)
        }
    }

    private fun preconditionForFact(fact: InitialFactAp): List<InitialFactAp>? {
        when (currentInst) {
            is JIRAssignInst -> {
                return sequentAssignPrecondition(currentInst.rhv, currentInst.lhv, fact)
            }

            is JIRReturnInst -> {
                if (fact.base !is AccessPathBase.Return) {
                    return null
                }

                val base = currentInst.returnValue
                    ?.let { MethodFlowFunctionUtils.accessPathBase(it) }
                    ?: return null

                return listOf(fact.rebase(base))
            }

            is JIRThrowInst -> {
                if (fact.base !is AccessPathBase.Exception) {
                    return null
                }

                val base = currentInst.throwable
                    .let { MethodFlowFunctionUtils.accessPathBase(it) }
                    ?: return null

                return listOf(fact.rebase(base))
            }

            else -> return null
        }
    }

    private fun sequentAssignPrecondition(
        assignFrom: JIRExpr,
        assignTo: JIRValue,
        fact: InitialFactAp,
    ): List<InitialFactAp>? {
        val assignFromAccess = when (assignFrom) {
            is JIRCastExpr -> MethodFlowFunctionUtils.mkAccess(assignFrom.operand)
            is JIRImmediate -> MethodFlowFunctionUtils.mkAccess(assignFrom)
            is JIRArrayAccess -> MethodFlowFunctionUtils.mkAccess(assignFrom)
            is JIRFieldRef -> MethodFlowFunctionUtils.mkAccess(assignFrom)
            else -> null
        }

        val assignToAccess = when (assignTo) {
            is JIRImmediate -> MethodFlowFunctionUtils.mkAccess(assignTo)
            is JIRArrayAccess -> MethodFlowFunctionUtils.mkAccess(assignTo)
            is JIRFieldRef -> MethodFlowFunctionUtils.mkAccess(assignTo)
            else -> null
        }

        return when {
            assignFromAccess?.accessor != null -> {
                check(assignToAccess?.accessor == null) { "Complex assignment: $assignTo = $assignFrom" }
                fieldRead(
                    assignToAccess?.base, assignFromAccess.base, assignFromAccess.accessor, fact
                )
            }

            assignToAccess?.accessor != null -> {
                fieldWrite(
                    assignToAccess.base, assignToAccess.accessor, assignFromAccess?.base, fact
                )
            }

            else -> simpleAssign(assignToAccess?.base, assignFromAccess?.base, fact)
        }
    }

    private fun simpleAssign(
        assignTo: AccessPathBase?,
        assignFrom: AccessPathBase?,
        fact: InitialFactAp,
    ): List<InitialFactAp>? {
        if (assignTo == assignFrom || assignTo != fact.base) {
            return null
        }

        if (assignFrom != null) {
            return listOf(fact.rebase(assignFrom))
        }

        // kill fact
        return emptyList()
    }

    private fun fieldRead(
        assignTo: AccessPathBase?,
        instance: AccessPathBase,
        accessor: Accessor,
        fact: InitialFactAp,
    ): List<InitialFactAp>? {
        if (fact.base != assignTo) {
            return null
        }

        return listOf(fact.prependAccessor(accessor).rebase(instance))
    }

    private fun fieldWrite(
        instance: AccessPathBase,
        accessor: Accessor,
        assignFrom: AccessPathBase?,
        fact: InitialFactAp,
    ): List<InitialFactAp>? {
        if (fact.base != instance || !fact.startsWithAccessor(accessor)) {
            return null
        }

        val facts = buildList {
            val factAtAccessor = fact.readAccessor(accessor) ?: error("No fact")
            if (assignFrom != null) {
                this += factAtAccessor.rebase(assignFrom)
            }

            val otherFact = fact.clearAccessor(accessor)
            if (otherFact != null) {
                this += otherFact
            }

            if (accessor is ElementAccessor) {
                this += factAtAccessor.prependAccessor(ElementAccessor)
            }
        }

        return facts
    }

    private fun MutableList<SequentPreconditionFacts>.unconditionalSourcesPrecondition(fact: InitialFactAp) {
        if (currentInst !is JIRAssignInst) return

        val rhvFieldRef = currentInst.rhv as? JIRFieldRef ?: return
        val field = rhvFieldRef.field.field
        if (!field.isStatic) return

        val lhv = accessPathBase(currentInst.lhv) ?: return
        if (fact.base != lhv) return

        val config = analysisContext.taint.taintConfig as TaintRulesProvider
        val sourceRules = config.sourceRulesForStaticField(field, currentInst).toList()
        if (sourceRules.isEmpty()) return

        val entryFactReader = InitialFactReader(fact.rebase(AccessPathBase.Return), apManager)
        val sourcePreconditionEvaluator = TaintSourceActionPreconditionEvaluator(
            entryFactReader, analysisContext.factTypeChecker, returnValueType = null
        )

        for (sourceRule in sourceRules) {
            if (sourceRule.condition !is ConstantTrue) {
                TODO("Field source with complex condition")
            }

            val assignedMarks = sourceRule.actionsAfter.maybeFlatMap {
                sourcePreconditionEvaluator.evaluate(sourceRule, it)
            }
            if (assignedMarks.isNone) continue

            val sourceActions = assignedMarks.getOrThrow().mapTo(hashSetOf()) { it.second }

            this += MethodSequentPrecondition.SequentSource(
                fact, TaintRulePrecondition.Source(sourceRule, sourceActions)
            )
        }
    }
}

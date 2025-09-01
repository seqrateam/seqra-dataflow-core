package org.seqra.dataflow.jvm.ap.ifds.analysis

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.Accessor
import org.seqra.dataflow.ap.ifds.ElementAccessor
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.dataflow.ap.ifds.analysis.MethodSequentFlowFunction
import org.seqra.dataflow.ap.ifds.analysis.MethodSequentFlowFunction.Sequent
import org.seqra.dataflow.ap.ifds.taint.TaintSinkTracker.VulnerabilityTriggerPosition
import org.seqra.dataflow.configuration.jvm.ConstantTrue
import org.seqra.dataflow.jvm.ap.ifds.CalleePositionToJIRValueResolver
import org.seqra.dataflow.jvm.ap.ifds.JIRMarkAwareConditionRewriter
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.accessPathBase
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.clearField
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.excludeField
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.mayReadField
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.mayRemoveAfterWrite
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.readFieldTo
import org.seqra.dataflow.jvm.ap.ifds.MethodFlowFunctionUtils.writeToField
import org.seqra.dataflow.jvm.ap.ifds.TaintConfigUtils.applyRuleWithAssumptions
import org.seqra.dataflow.jvm.ap.ifds.taint.FinalFactReader
import org.seqra.dataflow.jvm.ap.ifds.taint.TaintRulesProvider
import org.seqra.dataflow.jvm.ap.ifds.taint.TaintSourceActionEvaluator
import org.seqra.ir.api.jvm.JIRType
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
import org.seqra.util.onSome

class JIRMethodSequentFlowFunction(
    private val apManager: ApManager,
    private val analysisContext: JIRMethodAnalysisContext,
    private val currentInst: JIRInst,
): MethodSequentFlowFunction {
    private val factTypeChecker get() = analysisContext.factTypeChecker

    override fun propagateZeroToZero(): Set<Sequent> = buildSet {
        add(Sequent.ZeroToZero)

        applyUnconditionalSources()
    }

    override fun propagateZeroToFact(currentFactAp: FinalFactAp) = buildSet {
        propagate(
            factAp = currentFactAp,
            unchanged = { add(Sequent.Unchanged) },
            propagateFact = { fact ->
                add(Sequent.ZeroToFact(fact))
            },
            propagateFactWithAccessorExclude = { _, _ ->
                error("Zero to Fact edge can't be refined: $currentFactAp")
            }
        )
    }

    override fun propagateFactToFact(
        initialFactAp: InitialFactAp,
        currentFactAp: FinalFactAp
    ) = buildSet {
        propagate(
            factAp = currentFactAp,
            unchanged = { add(Sequent.Unchanged) },
            propagateFact = { fact ->
                add(Sequent.FactToFact(initialFactAp, fact))
            },
            propagateFactWithAccessorExclude = { fact, accessor ->
                val refinedInitial = initialFactAp.excludeField(accessor)
                val refinedFact = fact.excludeField(accessor)
                add(Sequent.FactToFact(refinedInitial, refinedFact))
            }
        )
    }

    override fun propagateNDFactToFact(
        initialFacts: Set<InitialFactAp>,
        currentFactAp: FinalFactAp
    ) = buildSet {
        propagate(
            factAp = currentFactAp,
            unchanged = { add(Sequent.Unchanged) },
            propagateFact = { fact ->
                add(Sequent.NDFactToFact(initialFacts, fact))
            },
            propagateFactWithAccessorExclude = { _, _ ->
                error("NDF2F edge can't be refined: $currentFactAp")
            }
        )
    }

    private fun propagate(
        factAp: FinalFactAp,
        unchanged: () -> Unit,
        propagateFact: (FinalFactAp) -> Unit,
        propagateFactWithAccessorExclude: (FinalFactAp, Accessor) -> Unit
    ) {
        when (currentInst) {
            is JIRAssignInst -> {
                sequentFlowAssign(
                    currentInst.rhv, currentInst.lhv, factAp,
                    unchanged, propagateFact, propagateFactWithAccessorExclude
                )
            }

            is JIRReturnInst -> {
                unchanged()

                val access = currentInst.returnValue?.let { accessPathBase(it) }
                if (access == factAp.base) {
                    val resultFact = factAp.rebase(AccessPathBase.Return)
                    propagateFact(resultFact)

                    applyMethodExitSinkRules(AccessPathBase.Return, resultFact)
                } else {
                    applyMethodExitSinkRules(AccessPathBase.Return, factAp)
                }
            }

            is JIRThrowInst -> {
                unchanged()

                val access = accessPathBase(currentInst.throwable)
                if (access == factAp.base) {
                    val resultFact = factAp.rebase(AccessPathBase.Exception)
                    propagateFact(resultFact)

                    applyMethodExitSinkRules(AccessPathBase.Exception, resultFact)
                } else {
                    applyMethodExitSinkRules(AccessPathBase.Exception, factAp)
                }
            }

            else -> {
                unchanged()
            }
        }
    }

    private fun sequentFlowAssign(
        assignFrom: JIRExpr,
        assignTo: JIRValue,
        currentFactAp: FinalFactAp,
        unchanged: () -> Unit,
        propagateFact: (FinalFactAp) -> Unit,
        propagateFactWithAccessorExclude: (FinalFactAp, Accessor) -> Unit
    ) {
        var fact = currentFactAp

        val assignFromAccess = when (assignFrom) {
            is JIRCastExpr -> MethodFlowFunctionUtils.mkAccess(assignFrom.operand)
                ?.apply { fact = filterFactBaseType(assignFrom.type, fact) ?: return }
                ?: return

            is JIRImmediate -> MethodFlowFunctionUtils.mkAccess(assignFrom)
                ?.apply { fact = filterFactBaseType(assignFrom.type, fact) ?: return }
                ?: return

            is JIRArrayAccess -> MethodFlowFunctionUtils.mkAccess(assignFrom)
                ?.apply { fact = filterFactBaseType(assignFrom.array.type, fact) ?: return }
                ?: return

            is JIRFieldRef -> MethodFlowFunctionUtils.mkAccess(assignFrom)
                ?.apply { fact = filterFactBaseType(assignFrom.instance?.type, fact) ?: return }
                ?.apply { fact = filterFactBaseType(assignFrom.field.enclosingType, fact) ?: return }
                ?: return

            else -> null
        }

        val assignToAccess = when (assignTo) {
            is JIRImmediate -> MethodFlowFunctionUtils.mkAccess(assignTo)
                ?.apply { fact = filterFactBaseType(assignTo.type, fact) ?: return }
                ?: return

            is JIRArrayAccess -> MethodFlowFunctionUtils.mkAccess(assignTo)
                ?.apply { fact = filterFactBaseType(assignTo.array.type, fact) ?: return }
                ?: return

            is JIRFieldRef -> MethodFlowFunctionUtils.mkAccess(assignTo)
                ?.apply { fact = filterFactBaseType(assignTo.instance?.type, fact) ?: return }
                ?.apply { fact = filterFactBaseType(assignTo.field.enclosingType, fact) ?: return }
                ?: return

            else -> error("Assign to complex value: $assignTo")
        }

        val factModified = fact != currentFactAp
        val onUnchanged: (FinalFactAp) -> Unit = if (factModified) propagateFact else { _ -> unchanged() }

        when {
            assignFromAccess?.accessor != null -> {
                check(assignToAccess.accessor == null) { "Complex assignment: $assignTo = $assignFrom" }
                fieldRead(
                    assignToAccess.base, assignFromAccess.base, assignFromAccess.accessor, fact,
                    onUnchanged, propagateFact, propagateFactWithAccessorExclude
                )
            }

            assignToAccess.accessor != null -> {
                fieldWrite(
                    assignToAccess.base, assignToAccess.accessor, assignFromAccess?.base, fact,
                    onUnchanged, propagateFact, propagateFactWithAccessorExclude
                )
            }

            else -> simpleAssign(assignToAccess.base, assignFromAccess?.base, fact, onUnchanged, propagateFact)
        }
    }

    private fun MethodFlowFunctionUtils.Access.filterFactBaseType(
        expectedType: JIRType?,
        factAp: FinalFactAp
    ): FinalFactAp? {
        if (factAp.base != this.base || expectedType == null) return factAp
        return factTypeChecker.filterFactByLocalType(expectedType, factAp)
    }

    private fun simpleAssign(
        assignTo: AccessPathBase,
        assignFrom: AccessPathBase?,
        factAp: FinalFactAp,
        unchanged: (FinalFactAp) -> Unit,
        propagateFact: (FinalFactAp) -> Unit,
    ) {
        if (assignTo == assignFrom) {
            unchanged(factAp)
            return
        }

        // Assign can't overwrite fact
        if (assignTo != factAp.base) {
            unchanged(factAp)
        }

        if (assignFrom == factAp.base) {
            propagateFact(factAp.rebase(assignTo))
        }
    }

    private fun fieldRead(
        assignTo: AccessPathBase,
        instance: AccessPathBase,
        accessor: Accessor,
        factAp: FinalFactAp,
        unchanged: (FinalFactAp) -> Unit,
        propagateFact: (FinalFactAp) -> Unit,
        propagateFactWithAccessorExclude: (FinalFactAp, Accessor) -> Unit
    ) {
        if (!factAp.mayReadField(instance, accessor)) {
            // Fact is irrelevant to current reading
            unchanged(factAp)
            return
        }

        if (factAp.isAbstract() && accessor !in factAp.exclusions) {
            val nonAbstractAp = factAp.removeAbstraction()
            if (nonAbstractAp != null) {
                fieldRead(
                    assignTo, instance, accessor, nonAbstractAp,
                    unchanged, propagateFact, propagateFactWithAccessorExclude
                )
            }

            propagateAbstractFactWithFieldExcluded(factAp, accessor, propagateFactWithAccessorExclude)

            return
        }

        check(factAp.startsWithAccessor(accessor))

        val newAp = factAp.readFieldTo(newBase = assignTo, field = accessor)
        propagateFact(newAp)

        // Assign can't overwrite fact
        if (assignTo != factAp.base) {
            unchanged(factAp)
        }
    }

    private fun fieldWrite(
        instance: AccessPathBase,
        accessor: Accessor,
        assignFrom: AccessPathBase?,
        factAp: FinalFactAp,
        unchanged: (FinalFactAp) -> Unit,
        propagateFact: (FinalFactAp) -> Unit,
        propagateFactWithAccessorExclude: (FinalFactAp, Accessor) -> Unit
    ) {
        if (assignFrom == instance) {
            if (factAp.base != instance) {
                // Fact is irrelevant to current writing
                unchanged(factAp)
                return
            } else {
                /**
                 * a.x = a | f(a)
                 * -------------------
                 * b = a | f(a), f(b)
                 * a.x = b | f(b), f(b -> a.x), f(a -> a / {x})
                 */

                val auxiliaryBase = AccessPathBase.LocalVar(-1) // b
                check(auxiliaryBase != instance)

                fieldWrite(
                    instance = instance,
                    accessor = accessor,
                    assignFrom = auxiliaryBase,
                    factAp = factAp.rebase(auxiliaryBase), // f(b)
                    unchanged = {
                        if (it.base != auxiliaryBase) {
                            unchanged(it)
                        }
                    },
                    propagateFact = {
                        if (it.base != auxiliaryBase) {
                            propagateFact(it)
                        }
                    },
                    propagateFactWithAccessorExclude = { f, a ->
                        if (f.base != auxiliaryBase) {
                            propagateFactWithAccessorExclude(f, a)
                        }
                    }
                )

                fieldWrite(
                    instance = instance,
                    accessor = accessor,
                    assignFrom = auxiliaryBase,
                    factAp = factAp, // f(a)
                    unchanged = {
                        if (it.base != auxiliaryBase) {
                            unchanged(it)
                        }
                    },
                    propagateFact = {
                        if (it.base != auxiliaryBase) {
                            propagateFact(it)
                        }
                    },
                    propagateFactWithAccessorExclude = { f, a ->
                        if (f.base != auxiliaryBase) {
                            propagateFactWithAccessorExclude(f, a)
                        }
                    }
                )

                return
            }
        }

        if (factAp.base == assignFrom) {
            // Original rhs fact
            unchanged(factAp)

            // New lhs fact
            val newAp = factAp.writeToField(newBase = instance, field = accessor)
            propagateFact(newAp)

            analysisContext.aliasAnalysis?.forEachAliasAtStatement(currentInst, newAp) { aliased ->
                propagateFact(aliased)
            }

            return
        }

        // We have fact on lhs and NO fact on the rhs -> remove fact from lhs

        // todo hack: keep fact on the array elements
        if (factAp.base == instance && accessor is ElementAccessor) {
            propagateFact(factAp)
            return
        }

        if (!factAp.mayRemoveAfterWrite(instance, accessor)) {
            // Fact is irrelevant to current writing
            unchanged(factAp)
            return
        }

        if (factAp.isAbstract() && accessor !in factAp.exclusions) {
            val nonAbstractAp = factAp.removeAbstraction()
            if (nonAbstractAp != null) {
                fieldWrite(
                    instance, accessor, assignFrom, nonAbstractAp,
                    unchanged, propagateFact, propagateFactWithAccessorExclude
                )
            }

            propagateAbstractFactWithFieldExcluded(factAp, accessor, propagateFactWithAccessorExclude)

            return
        }

        check(factAp.startsWithAccessor(accessor))

        val newAp = factAp.clearField(accessor) ?: return
        propagateFact(newAp)
    }

    private fun propagateAbstractFactWithFieldExcluded(
        factAp: FinalFactAp,
        accessor: Accessor,
        propagateFactWithAccessorExclude: (FinalFactAp, Accessor) -> Unit
    ) {
        val abstractAp = apManager.createAbstractAp(factAp.base, factAp.exclusions)
        propagateFactWithAccessorExclude(abstractAp, accessor)

        analysisContext.aliasAnalysis?.forEachAliasAtStatement(currentInst, abstractAp) { aliased ->
            propagateFactWithAccessorExclude(aliased, accessor)
        }
    }

    private fun applyMethodExitSinkRules(
        methodResult: AccessPathBase, fact: FinalFactAp
    ): Unit = with(analysisContext.taint) {
        val config = taintConfig as TaintRulesProvider
        val sinkRules = config.sinkRulesForMethodExit(currentInst.location.method, currentInst).toList()
        if (sinkRules.isEmpty()) return

        val resultFact = if (fact.base == methodResult) fact.rebase(AccessPathBase.Return) else fact
        val conditionFactReader = FinalFactReader(resultFact, apManager)

        val valueResolver = CalleePositionToJIRValueResolver(currentInst.location.method)
        val conditionRewriter = JIRMarkAwareConditionRewriter(
            valueResolver,
            analysisContext.factTypeChecker
        )

        sinkRules.applyRuleWithAssumptions(
            apManager, conditionRewriter,
            listOf(conditionFactReader),
            condition = { condition },
            storeAssumptions = { rule, facts ->
                taintSinkTracker.addSinkRuleAssumptions(rule, currentInst, facts)
            },
            currentAssumptions = { rule ->
                taintSinkTracker.currentSinkRuleAssumptions(rule, currentInst)
            }
        ) { rule, evaluatedFacts ->
            taintSinkTracker.addVulnerability(
                analysisContext.methodEntryPoint, evaluatedFacts.toHashSet(), currentInst, rule,
                vulnerabilityTriggerPosition = VulnerabilityTriggerPosition.AFTER_INST
            )
        }
    }

    private fun MutableSet<Sequent>.applyUnconditionalSources() {
        if (currentInst !is JIRAssignInst) return

        val rhvFieldRef = currentInst.rhv as? JIRFieldRef ?: return
        val field = rhvFieldRef.field.field
        if (!field.isStatic) return

        val config = analysisContext.taint.taintConfig as TaintRulesProvider
        val sourceRules = config.sourceRulesForStaticField(field, currentInst).toList()
        if (sourceRules.isEmpty()) return

        val lhv = accessPathBase(currentInst.lhv) ?: return

        val sourceEvaluator = TaintSourceActionEvaluator(
            apManager, ExclusionSet.Universe, factTypeChecker, returnValueType = null
        )

        for (sourceRule in sourceRules) {
            if (sourceRule.condition !is ConstantTrue) {
                TODO("Field source with complex condition")
            }

            for (action in sourceRule.actionsAfter) {
                sourceEvaluator.evaluate(sourceRule, action).onSome { evaluatedFacts ->
                    evaluatedFacts.mapTo(this) {
                        if (it.base !is AccessPathBase.Return) {
                            TODO("Field source with non-result assign")
                        }

                        Sequent.ZeroToFact(it.rebase(lhv))
                    }
                }
            }
        }
    }
}

package org.seqra.dataflow.jvm.ap.ifds

import org.seqra.ir.api.jvm.JIRClassType
import org.seqra.ir.api.jvm.JIRInstExtFeature
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.JIRType
import org.seqra.ir.api.jvm.cfg.JIRAssignInst
import org.seqra.ir.api.jvm.cfg.JIRCallInst
import org.seqra.ir.api.jvm.cfg.JIRCatchInst
import org.seqra.ir.api.jvm.cfg.JIRExpr
import org.seqra.ir.api.jvm.cfg.JIRExprVisitor
import org.seqra.ir.api.jvm.cfg.JIRGotoInst
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.ir.api.jvm.cfg.JIRInstList
import org.seqra.ir.api.jvm.cfg.JIRInstLocation
import org.seqra.ir.api.jvm.cfg.JIRInstRef
import org.seqra.ir.api.jvm.cfg.JIRLambdaExpr
import org.seqra.ir.api.jvm.cfg.JIRLocalVar
import org.seqra.ir.api.jvm.cfg.JIRNewExpr
import org.seqra.ir.api.jvm.cfg.JIRSpecialCallExpr
import org.seqra.ir.api.jvm.ext.cfg.callExpr
import org.seqra.ir.api.jvm.ext.toType
import org.seqra.ir.impl.cfg.JIRInstListImpl
import org.seqra.ir.impl.cfg.JIRInstLocationImpl
import org.seqra.ir.impl.cfg.TypedSpecialMethodRefImpl
import org.seqra.dataflow.jvm.ap.ifds.LambdaAnonymousClassFeature.JIRLambdaClass

class LambdaExpressionToAnonymousClassTransformerFeature(
    private val lambdaAnonymousClassFeature: LambdaAnonymousClassFeature
) : JIRInstExtFeature {
    override fun transformInstList(method: JIRMethod, list: JIRInstList<JIRInst>): JIRInstList<JIRInst> {
        val lambdas = list.filter { it.callExpr is JIRLambdaExpr }
        if (lambdas.isEmpty()) {
            return list
        }

        val modifiedInstructions = list.instructions.toMutableList()
        val maxLocalVarIndex = modifiedInstructions.maxOfOrNull { LocalVarMaxIndexFinder.find(it.operands) } ?: -1

        var generatedLocalVarIndex = maxLocalVarIndex + 1
        val modifiedIndices = hashMapOf<Int, List<Int>>()

        fun nextLocalVar(name: String, type: JIRType) = JIRLocalVar(generatedLocalVarIndex++, name, type)

        for (lambda in lambdas) {
            val location = lambda.location
            when (lambda) {
                is JIRAssignInst -> {
                    val lambdaExpr = lambda.rhv as? JIRLambdaExpr
                        ?: error("Incorrect assign inst: $lambda")

                    val lambdaClass = lambdaAnonymousClassFeature.generateLambda(location, lambdaExpr)
                    val lambdaType = lambdaClass.toType()

                    val lambdaConstructor = lambdaClass.declaredMethods.single { it.isConstructor }
                    val constructorRef = TypedSpecialMethodRefImpl(
                        lambdaType,
                        lambdaConstructor.name,
                        lambdaConstructor.parameters.map { it.type },
                        lambdaConstructor.returnType
                    )

                    val lambdaClassVar = nextLocalVar("lambda%${location.index}", lambdaType)

                    val generatedLocations = mutableListOf<JIRInstLocation>()

                    modifiedInstructions.addInstruction(location) { loc ->
                        generatedLocations += loc
                        JIRAssignInst(loc, lambdaClassVar, JIRNewExpr(lambdaType))
                    }

                    modifiedInstructions.addInstruction(location) { loc ->
                        generatedLocations += loc
                        val constructorCall = JIRSpecialCallExpr(constructorRef, lambdaClassVar, lambdaExpr.callSiteArgs)
                        JIRCallInst(loc, constructorCall)
                    }

                    modifiedInstructions.addInstruction(location) { loc ->
                        generatedLocations += loc
                        JIRAssignInst(loc, lambda.lhv, lambdaClassVar)
                    }

                    modifiedInstructions.addInstruction(location) { loc ->
                        generatedLocations += loc
                        JIRGotoInst(loc, JIRInstRef(location.index + 1))
                    }

                    val lambdaBlockStart = JIRInstRef(generatedLocations.first().index)
                    modifiedInstructions[location.index] = JIRGotoInst(location, lambdaBlockStart)

                    modifiedIndices[location.index] = generatedLocations.map { it.index }
                }

                is JIRCallInst -> {
                    // This lambda expression has no effect, skip it
                    val nopInst = JIRGotoInst(location, JIRInstRef(location.index + 1))
                    modifiedInstructions[location.index] = nopInst
                }

                else -> error("Unexpected lambda expression in $lambda")
            }
        }

        for (i in modifiedInstructions.indices) {
            val instruction = modifiedInstructions[i]
            if (instruction !is JIRCatchInst) continue

            val throwers = instruction.throwers.toMutableList()
            for (throwerIdx in throwers.indices) {
                val thrower = throwers[throwerIdx]
                val generatedLocations = modifiedIndices[thrower.index] ?: continue
                generatedLocations.mapTo(throwers) { JIRInstRef(it) }
            }

            modifiedInstructions[i] = with(instruction) {
                JIRCatchInst(location, throwable, throwableTypes, throwers)
            }
        }

        return JIRInstListImpl(modifiedInstructions)
    }

    private inline fun MutableList<JIRInst>.addInstruction(origin: JIRInstLocation, body: (JIRInstLocation) -> JIRInst) {
        val index = size
        val newLocation = JIRInstLocationImpl(origin.method, index, origin.lineNumber)
        val instruction = body(newLocation)
        check(size == index)
        add(instruction)
    }

    private object LocalVarMaxIndexFinder : JIRExprVisitor.Default<Int> {
        override fun defaultVisitJIRExpr(expr: JIRExpr) = find(expr.operands)
        override fun visitJIRLocalVar(value: JIRLocalVar) = value.index
        fun find(expressions: Iterable<JIRExpr>): Int = expressions.maxOfOrNull { it.accept(this) } ?: -1
    }

    companion object {
        fun findLambdaAllocation(inst: JIRInst): JIRLambdaClass? {
            val assign = inst as? JIRAssignInst ?: return null
            val allocation = assign.rhv as? JIRNewExpr ?: return null
            val allocatedClassType = allocation.type as? JIRClassType ?: return null
            return allocatedClassType.jIRClass as? JIRLambdaClass
        }
    }
}

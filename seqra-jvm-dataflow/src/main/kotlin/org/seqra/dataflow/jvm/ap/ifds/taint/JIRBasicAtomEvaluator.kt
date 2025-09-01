package org.seqra.dataflow.jvm.ap.ifds.taint

import org.seqra.dataflow.configuration.jvm.And
import org.seqra.dataflow.configuration.jvm.ConditionNameMatcher
import org.seqra.dataflow.configuration.jvm.ConditionVisitor
import org.seqra.dataflow.configuration.jvm.ConstantBooleanValue
import org.seqra.dataflow.configuration.jvm.ConstantEq
import org.seqra.dataflow.configuration.jvm.ConstantGt
import org.seqra.dataflow.configuration.jvm.ConstantIntValue
import org.seqra.dataflow.configuration.jvm.ConstantLt
import org.seqra.dataflow.configuration.jvm.ConstantMatches
import org.seqra.dataflow.configuration.jvm.ConstantStringValue
import org.seqra.dataflow.configuration.jvm.ConstantTrue
import org.seqra.dataflow.configuration.jvm.ConstantValue
import org.seqra.dataflow.configuration.jvm.ContainsMark
import org.seqra.dataflow.configuration.jvm.IsConstant
import org.seqra.dataflow.configuration.jvm.Not
import org.seqra.dataflow.configuration.jvm.Or
import org.seqra.dataflow.configuration.jvm.PositionResolver
import org.seqra.dataflow.configuration.jvm.TypeMatches
import org.seqra.dataflow.configuration.jvm.TypeMatchesPattern
import org.seqra.dataflow.jvm.ap.ifds.JIRFactTypeChecker
import org.seqra.ir.api.common.cfg.CommonValue
import org.seqra.ir.api.jvm.JIRRefType
import org.seqra.ir.api.jvm.cfg.JIRBool
import org.seqra.ir.api.jvm.cfg.JIRConstant
import org.seqra.ir.api.jvm.cfg.JIRInt
import org.seqra.ir.api.jvm.cfg.JIRStringConstant
import org.seqra.ir.api.jvm.cfg.JIRValue
import org.seqra.ir.api.jvm.ext.isAssignable
import org.seqra.util.Maybe
import org.seqra.util.onSome

class JIRBasicAtomEvaluator(
    private val negated: Boolean,
    private val positionResolver: PositionResolver<Maybe<JIRValue>>,
    private val typeChecker: JIRFactTypeChecker
) : ConditionVisitor<Boolean> {
    override fun visit(condition: Not): Boolean = error("Non-atomic condition")
    override fun visit(condition: And): Boolean = error("Non-atomic condition")
    override fun visit(condition: Or): Boolean = error("Non-atomic condition")

    override fun visit(condition: ContainsMark): Boolean {
        error("This visitor does not support condition $condition. Use FactAwareConditionEvaluator instead")
    }

    override fun visit(condition: ConstantTrue): Boolean {
        return true
    }

    override fun visit(condition: IsConstant): Boolean {
        positionResolver.resolve(condition.position).onSome {
            return isConstant(it)
        }
        return false
    }

    override fun visit(condition: ConstantEq): Boolean {
        positionResolver.resolve(condition.position).onSome { value ->
            return eqConstant(value, condition.value)
        }
        return false
    }

    override fun visit(condition: ConstantLt): Boolean {
        positionResolver.resolve(condition.position).onSome { value ->
            return ltConstant(value, condition.value)
        }
        return false
    }

    override fun visit(condition: ConstantGt): Boolean {
        positionResolver.resolve(condition.position).onSome { value ->
            return gtConstant(value, condition.value)
        }
        return false
    }

    override fun visit(condition: ConstantMatches): Boolean {
        positionResolver.resolve(condition.position).onSome { value ->
            return matches(value, condition.pattern)
        }
        return false
    }

    override fun visit(condition: TypeMatches): Boolean {
        positionResolver.resolve(condition.position).onSome { value ->
            return typeMatches(value, condition)
        }
        return false
    }

    private val typeMatchesCache = hashMapOf<TypeMatchesPattern, Boolean>()

    override fun visit(condition: TypeMatchesPattern): Boolean = typeMatchesCache.computeIfAbsent(condition) {
        positionResolver.resolve(condition.position).onSome { value ->
            return@computeIfAbsent typeMatchesPattern(value, condition)
        }
        return@computeIfAbsent false
    }

    private fun isConstant(value: JIRValue): Boolean {
        return value is JIRConstant
    }

    private fun eqConstant(value: JIRValue, constant: ConstantValue): Boolean {
        return when (constant) {
            is ConstantBooleanValue -> {
                when (value) {
                    is JIRBool -> value.value == constant.value
                    is JIRInt -> if (constant.value) value.value != 0 else value.value == 0
                    else -> false
                }
            }

            is ConstantIntValue -> {
                value is JIRInt && value.value == constant.value
            }

            is ConstantStringValue -> {
                // TODO: if 'value' is not string, convert it to string and compare with 'constant.value'
                value is JIRStringConstant && value.value == constant.value
            }
        }
    }

    private fun ltConstant(value: JIRValue, constant: ConstantValue): Boolean {
        return when (constant) {
            is ConstantIntValue -> {
                value is JIRInt && value.value < constant.value
            }

            else -> error("Unexpected constant: $constant")
        }
    }

    private fun gtConstant(value: JIRValue, constant: ConstantValue): Boolean {
        return when (constant) {
            is ConstantIntValue -> {
                value is JIRInt && value.value > constant.value
            }

            else -> error("Unexpected constant: $constant")
        }
    }

    private fun matches(value: JIRValue, pattern: Regex): Boolean {
        val s = value.toString()
        return pattern.matches(s)
    }

    private fun typeMatches(value: CommonValue, condition: TypeMatches): Boolean {
        check(value is JIRValue)
        return value.type.isAssignable(condition.type)
    }

    private fun typeMatchesPattern(value: JIRValue, condition: TypeMatchesPattern): Boolean {
        val type = value.type as? JIRRefType ?: return false

        when (val pattern = condition.pattern) {
            is ConditionNameMatcher.PatternEndsWith -> {
                if (type.typeName.endsWith(pattern.suffix)) return true

                // todo: check super classes?
                return false
            }

            is ConditionNameMatcher.PatternStartsWith -> {
                if (type.typeName.startsWith(pattern.prefix)) return true

                // todo: check super classes?
                return false
            }

            is ConditionNameMatcher.Pattern -> {
                if (pattern.pattern.containsMatchIn(type.typeName)) return true

                // todo: check super classes?
                return false
            }

            is ConditionNameMatcher.Concrete -> {
                if (pattern.name == type.typeName) return true

                if (negated) return false

                if (type.typeName == "java.lang.Object") {
                    // todo: hack to avoid explosion
                    return false
                }

                return typeChecker.typeMayHaveSubtypeOf(type.typeName, pattern.name)
            }
        }
    }
}

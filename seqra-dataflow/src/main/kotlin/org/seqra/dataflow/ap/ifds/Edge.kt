package org.seqra.dataflow.ap.ifds

import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp
import org.seqra.ir.api.common.cfg.CommonInst

sealed interface Edge {
    val methodEntryPoint: MethodEntryPoint
    val statement: CommonInst

    fun replaceStatement(newStatement: CommonInst): Edge

    sealed interface ZeroInitialEdge: Edge

    class ZeroToZero(
        override val methodEntryPoint: MethodEntryPoint,
        override val statement: CommonInst
    ) : ZeroInitialEdge {
        override fun replaceStatement(newStatement: CommonInst): Edge =
            ZeroToZero(methodEntryPoint, newStatement)

        override fun toString(): String = "(Z -> Z)[$methodEntryPoint -> $statement]]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ZeroToZero

            if (methodEntryPoint != other.methodEntryPoint) return false
            if (statement != other.statement) return false

            return true
        }

        override fun hashCode(): Int {
            var result = methodEntryPoint.hashCode()
            result = 31 * result + statement.hashCode()
            return result
        }
    }

    class ZeroToFact(
        override val methodEntryPoint: MethodEntryPoint,
        override val statement: CommonInst,
        val factAp: FinalFactAp
    ) : ZeroInitialEdge {

        init {
            check(factAp.exclusions is ExclusionSet.Universe) {
                "Incorrect ZeroToFact edge exclusion: $factAp"
            }
        }

        override fun replaceStatement(newStatement: CommonInst): Edge =
            ZeroToFact(methodEntryPoint, newStatement, factAp)

        override fun toString(): String = "(Z -> $factAp)[$methodEntryPoint -> $statement]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ZeroToFact

            if (methodEntryPoint != other.methodEntryPoint) return false
            if (statement != other.statement) return false
            if (factAp != other.factAp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = methodEntryPoint.hashCode()
            result = 31 * result + statement.hashCode()
            result = 31 * result + factAp.hashCode()
            return result
        }
    }

    class FactToFact(
        override val methodEntryPoint: MethodEntryPoint,
        val initialFactAp: InitialFactAp,
        override val statement: CommonInst,
        val factAp: FinalFactAp
    ) : Edge {

        init {
            check(factAp.exclusions !is ExclusionSet.Universe) {
                "Incorrect FactToFact edge exclusion: $factAp"
            }
        }

        override fun replaceStatement(newStatement: CommonInst): Edge =
            FactToFact(methodEntryPoint, initialFactAp, newStatement, factAp)

        override fun toString(): String = "($initialFactAp -> $factAp)[$methodEntryPoint -> $statement]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as FactToFact

            if (methodEntryPoint != other.methodEntryPoint) return false
            if (initialFactAp != other.initialFactAp) return false
            if (statement != other.statement) return false
            if (factAp != other.factAp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = methodEntryPoint.hashCode()
            result = 31 * result + initialFactAp.hashCode()
            result = 31 * result + statement.hashCode()
            result = 31 * result + factAp.hashCode()
            return result
        }
    }

    class NDFactToFact(
        override val methodEntryPoint: MethodEntryPoint,
        val initialFacts: Set<InitialFactAp>,
        override val statement: CommonInst,
        val factAp: FinalFactAp,
    ) : Edge {
        init {
            check(initialFacts.size > 1) {
                "Distributive edge"
            }

            check(initialFacts.all { it.exclusions is ExclusionSet.Universe }) {
                "Incorrect NDFactToFact edge exclusion: $initialFacts"
            }

            check(factAp.exclusions is ExclusionSet.Universe) {
                "Incorrect NDFactToFact edge exclusion: $factAp"
            }
        }

        override fun replaceStatement(newStatement: CommonInst) =
            NDFactToFact(methodEntryPoint, initialFacts, newStatement, factAp)

        override fun toString(): String =
            "(${initialFacts} -> $factAp)[$methodEntryPoint -> $statement]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NDFactToFact) return false

            if (methodEntryPoint != other.methodEntryPoint) return false
            if (initialFacts != other.initialFacts) return false
            if (statement != other.statement) return false
            if (factAp != other.factAp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = methodEntryPoint.hashCode()
            result = 31 * result + initialFacts.hashCode()
            result = 31 * result + statement.hashCode()
            result = 31 * result + factAp.hashCode()
            return result
        }
    }
}

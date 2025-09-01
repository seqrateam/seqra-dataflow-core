package org.seqra.dataflow.ap.ifds.access.automata

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.Accessor
import org.seqra.dataflow.ap.ifds.AnyAccessor
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp

data class AccessGraphInitialFactAp(
    override val base: AccessPathBase,
    val access: AccessGraph,
    override val exclusions: ExclusionSet,
) : InitialFactAp {
    override val size: Int get() = access.size

    override fun rebase(newBase: AccessPathBase): InitialFactAp =
        AccessGraphInitialFactAp(newBase, access, exclusions)

    override fun exclude(accessor: Accessor): InitialFactAp {
        check(accessor !is AnyAccessor)
        return AccessGraphInitialFactAp(base, access, exclusions.add(accessor))
    }

    override fun replaceExclusions(exclusions: ExclusionSet): InitialFactAp =
        AccessGraphInitialFactAp(base, access, exclusions)

    override fun getAllAccessors() =
        access.getAllOwnAccessors()

    override fun startsWithAccessor(accessor: Accessor): Boolean = with(access.manager) {
        check(accessor !is AnyAccessor)
        return access.startsWith(accessor.idx)
    }

    override fun readAccessor(accessor: Accessor): InitialFactAp? = with(access.manager) {
        check(accessor !is AnyAccessor)
        return access.read(accessor.idx)?.let { AccessGraphInitialFactAp(base, it, exclusions) }
    }

    override fun prependAccessor(accessor: Accessor): InitialFactAp = with(access.manager) {
        check(accessor !is AnyAccessor)
        return AccessGraphInitialFactAp(base, access.prepend(accessor.idx), exclusions)
    }

    override fun clearAccessor(accessor: Accessor): InitialFactAp? = with(access.manager) {
        check(accessor !is AnyAccessor)
        return access.clear(accessor.idx)?.let { AccessGraphInitialFactAp(base, it, exclusions) }
    }

    data class Delta(val graph: AccessGraph) : InitialFactAp.Delta {
        override val isEmpty: Boolean get() = graph.isEmpty()

        override fun concat(other: InitialFactAp.Delta): InitialFactAp.Delta {
            other as Delta

            return Delta(graph.concat(other.graph))
        }
    }

    override fun splitDelta(other: FinalFactAp): List<Pair<InitialFactAp, InitialFactAp.Delta>> {
        other as AccessGraphFinalFactAp
        if (base != other.base) return emptyList()

        if (other.access.isEmpty()) {
            val filteredDelta = this.access.filter(other.exclusions) ?: return emptyList()

            val emptyFact = AccessGraphInitialFactAp(base, access.manager.emptyGraph(), exclusions)
            return listOf(emptyFact to Delta(filteredDelta))
        }

        return access.splitDelta(other.access).mapNotNull { (matchedAccess, delta) ->
            val filteredDelta = delta.filter(other.exclusions) ?: return@mapNotNull null

            val matchedFact = AccessGraphInitialFactAp(base, matchedAccess, exclusions)
            matchedFact to Delta(filteredDelta)
        }
    }

    override fun concat(delta: InitialFactAp.Delta): InitialFactAp {
        if (delta.isEmpty) return this
        delta as Delta

        val concatenatedGraph = access.concat(delta.graph)
        return AccessGraphInitialFactAp(base, concatenatedGraph, exclusions)
    }

    override fun contains(factAp: InitialFactAp): Boolean {
        factAp as AccessGraphInitialFactAp

        if (base != factAp.base) return false
        return access.containsAll(factAp.access)
    }
}

package org.seqra.dataflow.jvm.ap.ifds.taint

import org.seqra.dataflow.ap.ifds.AccessPathBase
import org.seqra.dataflow.ap.ifds.Accessor
import org.seqra.dataflow.ap.ifds.ExclusionSet
import org.seqra.dataflow.ap.ifds.TaintMarkAccessor
import org.seqra.dataflow.ap.ifds.access.ApManager
import org.seqra.dataflow.ap.ifds.access.FactAp
import org.seqra.dataflow.ap.ifds.access.FinalFactAp
import org.seqra.dataflow.ap.ifds.access.InitialFactAp

fun ApManager.mkAccessPath(
    position: PositionAccess,
    exclusionSet: ExclusionSet,
    mark: String,
): FinalFactAp = mkAccessPath(
    position,
    // we use stub base and exclusion here
    createFinalAp(AccessPathBase.This, ExclusionSet.Universe).prependAccessor(TaintMarkAccessor(mark)),
    exclusionSet
)

fun ApManager.mkInitialAccessPath(
    position: PositionAccess,
    exclusionSet: ExclusionSet
): InitialFactAp = mkAccessPath(
    position,
    // we use stub base and exclusion here
    createFinalInitialAp(AccessPathBase.This, ExclusionSet.Universe),
    exclusionSet
)

fun mkAccessPath(position: PositionAccess, basicAp: FinalFactAp, exclusionSet: ExclusionSet): FinalFactAp =
    mkAccessPath(
        position = position,
        basicAp = basicAp,
        exclusionSet = exclusionSet,
        prependAccessor = { prependAccessor(it) },
        rebase = { rebase(it) },
        replaceExclusions = { replaceExclusions(it) }
    )

fun mkAccessPath(position: PositionAccess, basicAp: InitialFactAp, exclusionSet: ExclusionSet): InitialFactAp =
    mkAccessPath(
        position = position,
        basicAp = basicAp,
        exclusionSet = exclusionSet,
        prependAccessor = { prependAccessor(it) },
        rebase = { rebase(it) },
        replaceExclusions = { replaceExclusions(it) }
    )

fun <F : FactAp> mkAccessPath(
    position: PositionAccess,
    basicAp: F,
    exclusionSet: ExclusionSet,
    prependAccessor: F.(Accessor) -> F,
    rebase: F.(AccessPathBase) -> F,
    replaceExclusions: F.(ExclusionSet) -> F
): F {
    var currentPosition = position
    var result = basicAp
    while (true) {
        when (currentPosition) {
            is PositionAccess.Complex -> {
                result = result.prependAccessor(currentPosition.accessor)
                currentPosition = currentPosition.base
            }

            is PositionAccess.Simple -> {
                return result.rebase(currentPosition.base).replaceExclusions(exclusionSet)
            }
        }
    }
}
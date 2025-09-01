package org.seqra.dataflow.util

import it.unimi.dsi.fastutil.ints.IntSet
import java.util.BitSet

fun BitSet.add(element: Int): Boolean {
    if (get(element)) return false
    set(element)
    return true
}

fun bitSetOf(element: Int): BitSet = BitSet().also { it.set(element) }

operator fun BitSet.contains(element: Int): Boolean = get(element)

inline fun BitSet.forEach(action: (Int) -> Unit) {
    var node = nextSetBit(0)
    while (node >= 0) {
        action(node)
        node = nextSetBit(node + 1)
    }
}

fun BitSet.removeFirst(): Int {
    val node = nextSetBit(0)
    check(node >= 0) { "Set is empty" }
    clear(node)
    return node
}

fun BitSet.containsAll(other: BitSet): Boolean {
    val copy = other.clone() as BitSet
    copy.andNot(this@containsAll)
    return copy.isEmpty
}

fun BitSet.copy(): BitSet = clone() as BitSet

inline fun BitSet.filter(predicate: (Int) -> Boolean): BitSet {
    if (isEmpty) return this

    val result = BitSet(size())
    forEach { element ->
        if (predicate(element)) result.set(element)
    }
    return result
}

inline fun BitSet.map(body: (Int) -> Int): BitSet {
    if (isEmpty) return this

    val result = BitSet(size())
    forEach { element ->
        result.set(body(element))
    }
    return result
}

inline fun BitSet.any(predicate: (Int) -> Boolean): Boolean {
    forEach { element ->
        if (predicate(element)) return true
    }
    return false
}

inline fun <T> Iterable<T>.toBitSet(convert: (T) -> Int): BitSet {
    val result = BitSet()
    forEach { element -> result.set(convert(element)) }
    return result
}

fun IntSet.toBitSet(): BitSet {
    val result = BitSet()
    val iter = intIterator()
    while (iter.hasNext()) {
        result.set(iter.nextInt())
    }
    return result
}

fun BitSet.toSet(): Set<Int> {
    val result = mutableSetOf<Int>()
    forEach { element -> result.add(element) }
    return result
}

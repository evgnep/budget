package su.nepom.budget.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class RawMoney(val value: Long) {
    constructor(value: Int) : this(value.toLong())

    override fun toString(): String  = "${value}_rawMoney"

    companion object {
        val ZERO = RawMoney(0)
    }
}

operator fun RawMoney.plus(other: RawMoney): RawMoney = RawMoney(value + other.value)

operator fun RawMoney.minus(other: RawMoney): RawMoney = RawMoney(value - other.value)

val Int.rawMoney get() = RawMoney(this)

val Long.rawMoney get() = RawMoney(this)



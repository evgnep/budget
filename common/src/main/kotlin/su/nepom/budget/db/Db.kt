package su.nepom.budget.db

import su.nepom.budget.event.Event
import su.nepom.budget.model.ObjectKind
import java.util.*

interface Db: AutoCloseable {
    /**
     * @param name Session identifier for logging, thread name, etc
     * @param blockingMode Only this session can write to Db and only one such session can exist
     * @param createEvents Events on data change will be created and saved
     * @param autoCommit Each modifying operation will be auto-commited
     */
    fun createSession(
        name: String,
        blockingMode: Boolean = false,
        createEvents: Boolean = true,
        autoCommit: Boolean = false
    ): Session

    enum class SubscribeKind {
        CURRENCY,
        ACCOUNT,
        ACCOUNT_REST,
        TRANSACTION,
        ;
        companion object {
            val ALL = EnumSet.allOf(SubscribeKind::class.java)

            fun from(kind: ObjectKind): SubscribeKind = when(kind) {
                ObjectKind.CURRENCY -> CURRENCY
                ObjectKind.ACCOUNT -> ACCOUNT
                ObjectKind.TRANSACTION -> TRANSACTION
            }
        }
    }

    interface Subscription: AutoCloseable {
        fun unsubscribe() { close() }
    }

    fun subscribe(kinds: Set<SubscribeKind>, listener: DbListener): Subscription

    fun subscribe(vararg kind: SubscribeKind, listener: DbListener): Subscription =
        subscribe(kind.toSet(), listener)
}

typealias DbListener = (Collection<Event<*>>) -> Unit
package su.nepom.budget.db.sqlite.impl

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.asc
import org.ktorm.dsl.eq
import org.ktorm.dsl.isNull
import org.ktorm.entity.eachMaxBy
import org.ktorm.entity.filter
import org.ktorm.entity.groupingBy
import org.ktorm.entity.sortedBy
import su.nepom.budget.Global
import su.nepom.budget.db.sqlite.mapping.events
import su.nepom.budget.db.sqlite.utils.DatabaseHolder
import su.nepom.budget.model.Place


internal class EventProcessor(
    private val database: Database
): DatabaseHolder {
    private var lastEventCoordsHolder = mapOf<Place, Int>()

    init {
        updateLastEventCoords()
    }

    val lastEventCoords get() = lastEventCoordsHolder

    fun onTransactionFinished() {
        calculateLocalNos()
        updateLastEventCoords()
    }

    override fun getDb() = database

    private fun calculateLocalNos() {
        var lastNo = lastEventCoordsHolder[Global.currentPlace] ?: 0
        database.useTransaction {
            val toUpdate = events
                .filter { (it.source eq Global.currentPlace.code) and it.no.isNull() }
                .sortedBy { it.id.asc() }
            for (event in toUpdate) {
                event.no = ++lastNo
                event.flushChanges()
            }
        }
    }

    private fun updateLastEventCoords() {
        val lastEventCoords = mutableMapOf<Place, Int>()
        events.groupingBy { it.source }.eachMaxBy { it.no }.forEach { (place, eventNo) ->
            if (place != null && eventNo != null) lastEventCoords[Place(place)] = eventNo
        }
        this.lastEventCoordsHolder = lastEventCoords
    }
}
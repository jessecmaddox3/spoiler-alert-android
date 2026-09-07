package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class HistoryEventGroup(
    val eventKey: String,
    val eventName: String,
    val date: LocalDate,
    val items: List<VaultEntity>,
    val endDate: LocalDate = date,
    val mergeAdjacentDates: Boolean = false,
) {
    val lastPostedAt: Long get() = items.maxOf { it.postedAtMillis }
}

/** Event-first History grouping. Date separates repeat TEAM/manual sessions safely. */
object HistoryGrouping {
    fun group(
        items: List<VaultEntity>,
        zoneId: ZoneId,
        identity: (VaultEntity) -> HistoryEventIdentity = {
            HistoryEventIdentity("shield:${it.shieldId}", "Past session")
        },
    ): List<HistoryEventGroup> {
        val datedGroups = items.groupBy { item ->
            identity(item) to Instant.ofEpochMilli(item.postedAtMillis)
                .atZone(zoneId)
                .toLocalDate()
        }.map { (key, grouped) ->
            HistoryEventGroup(
                eventKey = key.first.key,
                eventName = key.first.displayName,
                date = key.second,
                items = grouped.sortedBy { it.postedAtMillis },
                mergeAdjacentDates = key.first.mergeAdjacentDates,
            )
        }
        return datedGroups.groupBy { it.eventKey }.values
            .flatMap(::mergeAdjacentEventDates)
            .sortedByDescending { it.lastPostedAt }
    }

    private fun mergeAdjacentEventDates(groups: List<HistoryEventGroup>): List<HistoryEventGroup> {
        val ordered = groups.sortedBy { it.date }
        if (ordered.none { it.mergeAdjacentDates }) return ordered
        return ordered.fold(mutableListOf()) { merged, next ->
            val previous = merged.lastOrNull()
            val adjacent = previous != null &&
                ChronoUnit.DAYS.between(previous.endDate, next.date) <= 1
            if (adjacent) {
                merged[merged.lastIndex] = previous.copy(
                    endDate = maxOf(previous.endDate, next.endDate),
                    items = (previous.items + next.items).sortedBy { it.postedAtMillis },
                )
            } else {
                merged += next
            }
            merged
        }
    }
}

data class HistoryEventIdentity(
    val key: String,
    val displayName: String,
    val mergeAdjacentDates: Boolean = false,
)

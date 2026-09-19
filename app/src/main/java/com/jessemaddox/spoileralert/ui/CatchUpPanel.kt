package com.jessemaddox.spoileralert.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.schedule.CatchUpQuestion
import com.jessemaddox.spoileralert.schedule.CatchUpRequest
import com.jessemaddox.spoileralert.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatchUpPanel(game: ProtectedLiveGame, state: CatchUpCheckState?, onAsk: (CatchUpRequest) -> Unit) {
    val soccer = game.leagueId in setOf("epl", "mls", "soccer")
    var viewingMinute by rememberSaveable(game.eventId) { mutableStateOf("") }
    var leadIn by rememberSaveable(game.eventId) { mutableStateOf(5) }
    var eastern by rememberSaveable(game.eventId) { mutableStateOf(false) }
    var options by rememberSaveable(game.eventId) { mutableStateOf(false) }
    // Visiting or restoring a page never automatically displays an earlier requested answer.
    var requested by remember(game.eventId) { mutableStateOf<CatchUpRequest?>(null) }
    fun ask(question: CatchUpQuestion) {
        val request = CatchUpRequest(question, viewingMinute.trim(), leadIn, eastern)
        requested = request
        onAsk(request)
    }
    val busy = state?.loading == true
    JessCard {
        Text("Catch-up summary", style = MaterialTheme.typography.headlineSmall, color = Blueberry)
        Text(
            if (soccer) "Current match progress and total goals. Add your viewing point for a place to resume."
            else if (game.leagueId == "golf") "Current round and whether the tournament has started or finished."
            else "Current game progress, without the score or who's ahead.",
            style = MaterialTheme.typography.bodyMedium, color = JessColors.subtle,
        )
        PrimaryPill("Get catch-up summary", { ask(CatchUpQuestion.SUMMARY) },
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        if (soccer) {
            OutlinedTextField(
                value = viewingMinute,
                onValueChange = { viewingMinute = it.take(12) },
                label = { Text("My game clock (optional)") },
                placeholder = { Text("10, 63:20, or 45+2") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Use the clock on your screen, e.g. 10, 63:20, or 45+2. Leave blank for all goal minutes.",
                style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostPill("Goal minutes", { ask(CatchUpQuestion.GOAL_TIMES) }, enabled = !busy)
                GhostPill("Where should I resume?", { ask(CatchUpQuestion.RESUME) }, enabled = !busy)
            }
            TextAction(if (options) "Close viewing options" else "Viewing options · ${leadIn} min lead-in",
                { options = !options })
            if (options) {
                Text("Buildup before the next goal", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 5, 10).forEach { minutes ->
                        FilterChip(leadIn == minutes, { leadIn = minutes }, label = { Text("$minutes min") })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Include approximate Eastern time", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(eastern, { eastern = it })
                }
                Text("These cues check goals. Cards, chances, and other incidents aren't included yet.",
                    style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
            }
        }
        val activeRequest = requested?.takeIf {
            it.viewingMinute == viewingMinute.trim() && it.leadInMinutes == leadIn && it.easternTime == eastern
        }
        if (activeRequest != null && state?.request == activeRequest) {
            HorizontalDivider(color = JessColors.hairline)
            if (busy) {
                Text("Checking the public game feed…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(when (activeRequest.question) {
                    CatchUpQuestion.SUMMARY -> "Your catch-up summary"
                    CatchUpQuestion.GOAL_TIMES -> "What minute was each goal scored?"
                    CatchUpQuestion.RESUME -> "Where should I resume?"
                }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(state.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Blueberry)
                state.checkedAtMillis?.let { Text(
                    "Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}",
                    style = MaterialTheme.typography.bodySmall, color = JessColors.subtle,
                ) }
                TextAction("Check again", { ask(activeRequest.question) })
            }
        }
    }
}

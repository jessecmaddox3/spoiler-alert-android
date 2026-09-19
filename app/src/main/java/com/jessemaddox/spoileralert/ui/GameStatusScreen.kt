package com.jessemaddox.spoileralert.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.schedule.ScoreAnswer
import com.jessemaddox.spoileralert.schedule.ScoreCheckpoint
import com.jessemaddox.spoileralert.schedule.ScoreQuestion
import com.jessemaddox.spoileralert.schedule.ScoreQuestionCatalog
import com.jessemaddox.spoileralert.schedule.SkipAnswer
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.GhostPill
import com.jessemaddox.spoileralert.ui.theme.JessCard
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.PrimaryPill
import com.jessemaddox.spoileralert.ui.theme.StatusChip
import com.jessemaddox.spoileralert.ui.theme.TextAction
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameStatusScreen(
    game: ProtectedLiveGame,
    scoreCheck: ScoreCheckState?,
    timelineState: GameTimelineState?,
    catchUpState: CatchUpCheckState?,
    onCatchUp: (com.jessemaddox.spoileralert.schedule.CatchUpRequest) -> Unit,
    onAsk: (ScoreQuestion, forceRefresh: Boolean) -> Unit,
    onLoadTimeline: (forceRefresh: Boolean) -> Unit,
    onAskEarlier: (ScoreCheckpoint, ScoreQuestion) -> Unit,
    onCheckSkip: (ScoreCheckpoint, ScoreCheckpoint, forceRefresh: Boolean) -> Unit,
) {
    val answers = remember(game.eventId) { mutableStateMapOf<ScoreQuestion, ScoreCheckState>() }
    val requestedQuestions = remember(game.eventId) { mutableStateMapOf<ScoreQuestion, Boolean>() }
    LaunchedEffect(scoreCheck) {
        if (scoreCheck?.loading == false && scoreCheck.answer != null && requestedQuestions[scoreCheck.question] == true) {
            answers[scoreCheck.question] = scoreCheck
        }
    }
    var requestedEarlier by remember(game.eventId) { mutableStateOf<Pair<String, ScoreQuestion>?>(null) }
    var requestedSkip by remember(game.eventId) { mutableStateOf<Pair<String, String>?>(null) }
    val visibleTimeline = timelineState?.copy(
        historicalAnswer = timelineState.historicalAnswer?.takeIf {
            requestedEarlier?.first == timelineState.historicalCheckpointId && requestedEarlier?.second == it.question
        },
        skipAnswer = timelineState.skipAnswer?.takeIf {
            requestedSkip?.first == timelineState.skipStartId && requestedSkip?.second == timelineState.skipEndId
        },
    )
    val questions = ScoreQuestionCatalog.forLeague(game.leagueId)
    val supportsTimeline = game.leagueId !in setOf("golf", "tgl", "f1")
    var delayTool by remember(game.eventId) { mutableStateOf(0) }
    LaunchedEffect(game.eventId, supportsTimeline) {
        if (supportsTimeline) onLoadTimeline(false)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(game.name, style = MaterialTheme.typography.headlineSmall, color = Blueberry)
            Text(
                "Ask only what you want to know. Answers stay hidden until you choose a question.",
                style = MaterialTheme.typography.bodyMedium,
                color = JessColors.subtle,
            )
        }
        item(key = "catch-up-summary") { CatchUpPanel(game, catchUpState, onCatchUp) }
        item(key = "questions-heading") {
            Text("ASK ONE THING", style = MaterialTheme.typography.labelMedium, color = JessColors.accentInk)
            Text("About the latest checked game state", style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle)
        }
        questions.forEach { spec ->
            val question = spec.question
            item(key = question.name) {
                val loading = scoreCheck?.loading == true && scoreCheck.question == question
                val result = answers[question]
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            requestedQuestions[question] = true
                            onAsk(question, result != null)
                        },
                        enabled = scoreCheck?.loading != true,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(spec.label, style = MaterialTheme.typography.titleMedium,
                                color = Blueberry, modifier = Modifier.weight(1f))
                            Text(if (result == null) "›" else "↻", color = JessColors.accentInk)
                        }
                    }
                    if (loading) ScoreRefreshIndicator("Checking…")
                    if (result != null) {
                        Text(result.detail ?: when (result.answer) {
                            ScoreAnswer.YES -> "Yes"
                            ScoreAnswer.NO -> "No"
                            else -> "Couldn't get a reliable answer yet"
                        }, style = MaterialTheme.typography.titleLarge, color = Blueberry)
                        result.checkedAtMillis?.let { checkedAt ->
                            Text("Latest state · checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(checkedAt))}",
                                style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = JessColors.hairline)
                }
            }
        }
        if (supportsTimeline) {
            item(key = "delay-heading") {
                Text("WATCHING ON DELAY", style = MaterialTheme.typography.labelMedium, color = JessColors.accentInk)
            }
            item(key = "delay-tools") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DelayToolCard(
                        title = "Check a stretch for scoring",
                        description = "Choose the stretch you would skip. We only tell you whether a score happens.",
                        expanded = delayTool == 1,
                        onToggle = { delayTool = if (delayTool == 1) 0 else 1 },
                    ) {
                        SkipAheadTool(
                            state = visibleTimeline,
                            onCheck = { start, end, force ->
                                requestedSkip = start.id to end.id
                                onCheckSkip(start, end, force)
                            },
                            onRefresh = { onLoadTimeline(true) },
                        )
                    }
                    DelayToolCard(
                        title = "Ask about an earlier point",
                        description = "Pick where you are in the game, then ask without seeing the score.",
                        expanded = delayTool == 2,
                        onToggle = { delayTool = if (delayTool == 2) 0 else 2 },
                    ) {
                        EarlierPointTool(
                            state = visibleTimeline,
                            questions = questions,
                            onAsk = { point, question ->
                                requestedEarlier = point.id to question
                                onAskEarlier(point, question)
                            },
                            onRefresh = { onLoadTimeline(true) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DelayToolCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    JessCard {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Blueberry)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
                }
                Text(if (expanded) "⌃" else "⌄", color = JessColors.accentInk,
                    modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (expanded) content()
    }
}

@Composable
private fun SkipAheadTool(
    state: GameTimelineState?,
    onCheck: (ScoreCheckpoint, ScoreCheckpoint, forceRefresh: Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val checkpoints = state?.checkpoints.orEmpty()
    when {
        state == null || state.loading -> ScoreRefreshIndicator("Loading game timeline…")
        state.sourceUnavailable || checkpoints.size < 2 -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "A detailed play timeline is not available for this event yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = JessColors.subtle,
            )
            TextAction("Check again", onRefresh)
        }
        else -> {
            var selectedRange by remember(checkpoints) {
                mutableStateOf(0f..checkpoints.lastIndex.toFloat())
            }
            val startIndex = selectedRange.start.roundToInt().coerceIn(checkpoints.indices)
            val endIndex = selectedRange.endInclusive.roundToInt().coerceIn(checkpoints.indices)
            val start = checkpoints[startIndex]
            val end = checkpoints[endIndex]
            Text("I’m currently at", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            Text(start.label, style = MaterialTheme.typography.titleMedium, color = Blueberry)
            Text("I want to skip to", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            Text(end.label, style = MaterialTheme.typography.titleMedium, color = Blueberry)
            RangeSlider(
                value = selectedRange,
                onValueChange = { selectedRange = it },
                valueRange = 0f..checkpoints.lastIndex.toFloat(),
                steps = (checkpoints.size - 2).coerceAtLeast(0),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Don’t let me miss", style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
                StatusChip("Any score")
            }
            when {
                state.skipLoading -> ScoreRefreshIndicator("Checking this stretch…")
                state.skipAnswer != null && state.skipStartId == start.id && state.skipEndId == end.id -> {
                    val (headline, supporting, color) = when (state.skipAnswer) {
                        SkipAnswer.SAFE -> Triple(
                            "No scoring recorded in this stretch",
                            "This checks scoring only. Other important moments may happen here.",
                            JessColors.revealGreen,
                        )
                        SkipAnswer.DO_NOT_SKIP -> Triple(
                            "Don’t skip this stretch",
                            "Something you asked not to miss happens here.",
                            JessColors.protectionRed,
                        )
                        SkipAnswer.UNAVAILABLE -> Triple(
                            "Couldn’t check this stretch",
                            "The play history is incomplete or still updating.",
                            JessColors.subtle,
                        )
                    }
                    Text(headline, style = MaterialTheme.typography.titleLarge, color = color)
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = JessColors.subtle)
                    state.skipCheckedAtMillis?.let { checkedAt ->
                        Text(
                            "Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(checkedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = JessColors.subtle,
                        )
                    }
                    TextAction("Check again", { onCheck(start, end, true) })
                }
                else -> PrimaryPill(
                    text = "Check this stretch",
                    onClick = { onCheck(start, end, false) },
                    enabled = endIndex > startIndex,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EarlierPointTool(
    state: GameTimelineState?,
    questions: List<com.jessemaddox.spoileralert.schedule.ScoreQuestionSpec>,
    onAsk: (ScoreCheckpoint, ScoreQuestion) -> Unit,
    onRefresh: () -> Unit,
) {
    val checkpoints = state?.checkpoints.orEmpty()
    when {
        state == null || state.loading -> ScoreRefreshIndicator("Loading game timeline…")
        state.sourceUnavailable || checkpoints.isEmpty() -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "An earlier game state is not available for this event yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = JessColors.subtle,
            )
            TextAction("Check again", onRefresh)
        }
        else -> {
            var selectedIndex by remember(checkpoints) { mutableStateOf(checkpoints.lastIndex.toFloat()) }
            val checkpoint = checkpoints[selectedIndex.roundToInt().coerceIn(checkpoints.indices)]
            Text("My viewing point", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            Text(checkpoint.label, style = MaterialTheme.typography.titleLarge, color = Blueberry)
            Slider(
                value = selectedIndex,
                onValueChange = { selectedIndex = it },
                valueRange = 0f..checkpoints.lastIndex.toFloat(),
                steps = (checkpoints.size - 2).coerceAtLeast(0),
            )
            val usefulQuestions = questions.filter {
                it.question in setOf(
                    ScoreQuestion.ANY_SCORE,
                    ScoreQuestion.TIED,
                    ScoreQuestion.CLOSE,
                    ScoreQuestion.BLOWOUT,
                    ScoreQuestion.OVER,
                )
            }
            Text("Ask as of this point", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usefulQuestions.forEach { spec ->
                    GhostPill(
                        text = spec.label,
                        onClick = { onAsk(checkpoint, spec.question) },
                        enabled = state.historicalLoading.not(),
                    )
                }
            }
            if (state.historicalLoading) {
                ScoreRefreshIndicator("Checking that point…")
            } else if (state.historicalCheckpointId == checkpoint.id) {
                state.historicalAnswer?.let { result ->
                    Text(questions.firstOrNull { it.question == result.question }?.label.orEmpty(),
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (result.answer) {
                            ScoreAnswer.YES -> "Yes"
                            ScoreAnswer.NO -> "No"
                            ScoreAnswer.UNAVAILABLE, null -> "Not available for that point"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Blueberry,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreRefreshIndicator(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = JessColors.accentInk,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
    }
}

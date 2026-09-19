package com.jessemaddox.spoileralert.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.ui.theme.Blue50
import com.jessemaddox.spoileralert.ui.theme.JessColors

/** Generic artwork. The public source tree deliberately omits third-party team marks. */
@Composable
fun TeamArtwork(
    teamId: String?,
    leagueId: String?,
    fallbackEmoji: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    val football = leagueId == "nfl" || leagueId == "cfb"
    Box(
        modifier.size(size).clip(CircleShape).background(Blue50),
        contentAlignment = Alignment.Center,
    ) {
        if (football) {
            HelmetOutline(
                Modifier.size(size * 0.86f),
                color = JessColors.accentInk.copy(alpha = 0.35f),
            )
        }
        androidx.compose.material3.Text(
            fallbackEmoji,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
fun MatchupArtwork(
    leagueId: String?,
    homeTeamId: String?,
    awayTeamId: String?,
    fallbackEmoji: String,
    modifier: Modifier = Modifier,
) {
    if (homeTeamId == null && awayTeamId == null) {
        TeamArtwork(null, leagueId, fallbackEmoji, modifier)
        return
    }
    Row(modifier) {
        TeamArtwork(awayTeamId, leagueId, fallbackEmoji, size = 38.dp)
        TeamArtwork(
            homeTeamId,
            leagueId,
            fallbackEmoji,
            size = 38.dp,
            modifier = Modifier.offset(x = (-8).dp),
        )
    }
}

fun TeamCatalog.artworkId(leagueId: String, espnId: String): String? =
    teamByEspnId(leagueId, espnId)?.id

@Composable
private fun HelmetOutline(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val helmet = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.60f)
            cubicTo(
                size.width * 0.14f, size.height * 0.28f,
                size.width * 0.36f, size.height * 0.12f,
                size.width * 0.62f, size.height * 0.16f,
            )
            cubicTo(
                size.width * 0.84f, size.height * 0.20f,
                size.width * 0.90f, size.height * 0.42f,
                size.width * 0.80f, size.height * 0.62f,
            )
            lineTo(size.width * 0.61f, size.height * 0.62f)
            lineTo(size.width * 0.58f, size.height * 0.78f)
            lineTo(size.width * 0.31f, size.height * 0.78f)
            close()
        }
        drawPath(helmet, color = color.copy(alpha = 0.12f))
        drawPath(helmet, color = color, style = Stroke(width = 1.5.dp.toPx()))
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(size.width * 0.60f, size.height * 0.48f),
            size = Size(size.width * 0.27f, size.height * 0.25f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.72f, size.height * 0.68f),
            end = Offset(size.width * 0.91f, size.height * 0.68f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

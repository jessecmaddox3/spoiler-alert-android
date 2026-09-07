package com.jessemaddox.spoileralert.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.R

// Radii from the token table.
val RadiusSm = 8.dp
val RadiusMd = 16.dp
val RadiusLg = 24.dp
val PillShape = CircleShape

/** Press state per the motion spec: scale(0.97), 120ms, no bounce. */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Eyebrow label: Roboto Condensed 700, uppercase, +0.08em, accent-ink by default. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = JessColors.accentInk,
    small: Boolean = false,
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        color = color,
    )
}

/** Section label ("YOUR TEAMS", "REVEALED EARLIER"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Eyebrow(text, modifier = modifier, small = true)
}

/** Primary pill: Wolt Blue fill, Blueberry text. */
@Composable
fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interaction).defaultMinSize(minHeight = 44.dp),
        enabled = enabled,
        interactionSource = interaction,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = WoltBlue, contentColor = Blueberry,
            disabledContainerColor = Blue50, disabledContentColor = JessColors.subtle,
        ),
        contentPadding = if (compact) PaddingValues(horizontal = 18.dp, vertical = 10.dp)
        else PaddingValues(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** On a blue hero the primary action is a Blueberry pill with white text. */
@Composable
fun NavyPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interaction).defaultMinSize(minHeight = 44.dp),
        interactionSource = interaction,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = Blueberry, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Ghost pill: 1.5px Blueberry-35% stroke, transparent fill, Blueberry text. */
@Composable
fun GhostPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pressScale(interaction).defaultMinSize(minHeight = 40.dp),
        enabled = enabled,
        interactionSource = interaction,
        shape = PillShape,
        border = BorderStroke(1.5.dp, if (enabled) JessColors.ghostStroke else JessColors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Blueberry, disabledContentColor = JessColors.subtle,
        ),
        contentPadding = if (compact) PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        else PaddingValues(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Reminder action with timing inside the control, so the consequence is self-contained. */
@Composable
fun ReminderPill(
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pressScale(interaction).defaultMinSize(minHeight = 52.dp),
        enabled = enabled,
        interactionSource = interaction,
        shape = PillShape,
        border = BorderStroke(1.5.dp, if (enabled) JessColors.ghostStroke else JessColors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Blueberry,
            disabledContentColor = JessColors.subtle,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Remind me", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = JessColors.subtle)
        }
    }
}

/** Loud but non-flashing active indicator. Red is reserved for active notification hiding. */
@Composable
fun ActiveHidingBanner(modifier: Modifier = Modifier) {
    Row(
        modifier
            .border(2.dp, ProtectionRed, RoundedCornerShape(12.dp))
            .padding(3.dp)
            .border(1.dp, ProtectionRed.copy(alpha = 0.38f), RoundedCornerShape(9.dp))
            .background(ProtectionRed50, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).background(ProtectionRed, CircleShape))
        Text(
            "HIDING NOTIFICATIONS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = ProtectionRed,
        )
    }
}

/** Green indicates the deliberate, user-controlled exit from protection and reveal. */
@Composable
fun StopAndRevealPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interaction).defaultMinSize(minHeight = 48.dp),
        interactionSource = interaction,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = RevealGreen, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_visibility),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Text action: accent-ink, DM Sans 700, no container. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = JessColors.accentInk,
        )
    }
}

/** Small text affordance ("Remove") with a 48dp touch target around the visual label. */
@Composable
fun SmallAction(
    text: String,
    onClick: () -> Unit,
    color: Color,
    contentDesc: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = contentDesc },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Status chip (pill). */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    container: Color = Blue50,
    content: Color = JessColors.accentInk,
) {
    Row(
        modifier = modifier
            .background(container, PillShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = content)
    }
}

/** Standard white card: radius 16, soft shadow, no border. */
@Composable
fun JessCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusMd),
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Blueberry),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Promoted card (next game): Blue-50 fill, no shadow. */
@Composable
fun PromotedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusMd),
        colors = CardDefaults.cardColors(containerColor = Blue50, contentColor = Blueberry),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** De-emphasized card: white, hairline border, no shadow (revealed-earlier). */
@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, JessColors.hairline, RoundedCornerShape(RadiusMd))
            .background(Color.White, RoundedCornerShape(RadiusMd))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Warning banner: amber fill and Blueberry ink. Red is reserved for active hiding. */
@Composable
fun WarningBanner(title: String, body: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusMd),
        colors = CardDefaults.cardColors(containerColor = Amber, contentColor = Blueberry),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
            TextAction(action, onClick = onAction)
        }
    }
}

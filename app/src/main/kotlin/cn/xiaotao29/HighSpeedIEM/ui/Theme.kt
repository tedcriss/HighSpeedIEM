package cn.xiaotao29.HighSpeedIEM.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2DF),
    onPrimaryContainer = Color(0xFF003D35),
    secondary = Color(0xFF4A635D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E0),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF416277),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC4E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
    background = Color(0xFFF6FAF7),
    onBackground = Color(0xFF17211E),
    surface = Color(0xFFF6FAF7),
    onSurface = Color(0xFF17211E),
    surfaceVariant = Color(0xFFDDE5E1),
    onSurfaceVariant = Color(0xFF465650),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F2),
    surfaceContainer = Color(0xFFE9EFEB),
    surfaceContainerHigh = Color(0xFFE3E9E5),
    surfaceContainerHighest = Color(0xFFDDE4E0),
    outline = Color(0xFF869490),
    outlineVariant = Color(0xFFBACAC2),
    inverseSurface = Color(0xFF2B322F),
    inverseOnSurface = Color(0xFFEDF2EE),
    inversePrimary = Color(0xFF82D5C5),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF82D5C5),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9EF2DF),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF324B45),
    onSecondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFFA8CBEA),
    onTertiary = Color(0xFF0B3448),
    tertiaryContainer = Color(0xFF294B60),
    onTertiaryContainer = Color(0xFFC4E7FF),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303634),
    outline = Color(0xFF8A938E),
    outlineVariant = Color(0xFF404944),
    inverseSurface = Color(0xFFDEE4E0),
    inverseOnSurface = Color(0xFF2B322F),
    inversePrimary = Color(0xFF006A60),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val DefaultTypography = Typography()
private val AppTypography = DefaultTypography.copy(
    titleLarge = DefaultTypography.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = DefaultTypography.titleMedium.copy(fontWeight = FontWeight.Medium),
)

/** Resolve the M3 disabled fill on an opaque surface before adding shadows.
 * A translucent fill over a manual shadow makes disabled floating buttons dirty.
 */
internal object ActionStyle {
    // M3 small-button visual height (40dp); touch-target sizing stays native.
    val containerHeight = ButtonDefaults.MinHeight
    // Shared native elevation for every state of the four primary action controls.
    val elevation = 6.dp

    @Composable
    fun buttonElevation() = ButtonDefaults.buttonElevation(
        defaultElevation = elevation,
        pressedElevation = elevation,
        focusedElevation = elevation,
        hoveredElevation = elevation,
        disabledElevation = elevation,
    )

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun toggleElevation() = ToggleButtonDefaults.elevation(
        defaultElevation = elevation,
        pressedElevation = elevation,
        focusedElevation = elevation,
        hoveredElevation = elevation,
        disabledElevation = elevation,
    )

    val disabledContainer: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            .compositeOver(MaterialTheme.colorScheme.surface)
    val disabledContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    @Composable
    fun primaryColors() = ButtonDefaults.buttonColors(
        disabledContainerColor = disabledContainer,
        disabledContentColor = disabledContent,
    )

    @Composable
    fun tonalColors() = ButtonDefaults.filledTonalButtonColors(
        disabledContainerColor = disabledContainer,
        disabledContentColor = disabledContent,
    )
}

/** Draws nothing: used to disable ripple/press feedback app-wide. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()
    override fun hashCode(): Int = -2
    override fun equals(other: Any?) = other === this
}

private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

/** Material 3 theme following the system dark mode. */
@Composable
fun HighSpeedIEMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        shapes = AppShapes,
        typography = AppTypography,
    ) {
        // Ripple/press indication stays disabled app-wide (user preference,
        // 2026-10-09: the ripple look was rejected). Residual-state hygiene
        // lives in the controls themselves: cards draw a ring only while
        // selected/focused; dropdown anchors clear focus on dismiss.
        CompositionLocalProvider(LocalIndication provides NoIndication) {
            content()
        }
    }
}

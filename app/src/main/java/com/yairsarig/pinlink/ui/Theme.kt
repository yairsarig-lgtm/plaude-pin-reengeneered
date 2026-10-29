package com.yairsarig.pinlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yairsarig.pinlink.R

/** Pocket Ideas look: warm off-white, soft cards, thin outlines, ideas set in Gelasio (bundled, OFL). */
object Pocket {
    val background = Color(0xFFFAFAF7)
    val ink = Color(0xFF141414)
    val muted = Color(0xFF8C8B86)
    val card = Color(0xFFF2F1EC)
    val pill = Color.White
    val frame = Color(0x1F000000) // rgba(0,0,0,0.12): light outlines (Recent actions card, settings pills)
    val outline = Color(0x52000000) // rgba(0,0,0,0.32): header buttons and the Filter pill
    val divider = Color(0x14000000)
    val green = Color(0xFF7A9A7E) // sage: connected and synced
    val amber = Color(0xFFE0A23A) // working
    val blue = Color(0xFF6F8FB8) // sent to Claude, waiting
    val grey = Color(0xFFB8B8B8)
    val danger = Color(0xFFC0392B)
    val reviewCard = Color(0xFFFBF3EA)
    val reviewPill = Color(0xFFF8E3C4)
    val reviewText = Color(0xFF8A5A14)

    val corner = RoundedCornerShape(16.dp)
    val pillShape = RoundedCornerShape(50)
    val cardElevation = 1.5.dp
    val shadow = Color(0x14000000)
    val iconStroke = 1.5f
    val gutter = 20.dp
    val pulseMs = 900
    val pulseMinAlpha = 0.35f

    val serif = FontFamily(
        Font(R.font.gelasio, FontWeight.Normal),
        Font(R.font.gelasio_italic, FontWeight.Normal, FontStyle.Italic),
    )

    val wordmark = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-1.4).sp, color = ink)
    val dateLine = TextStyle(fontFamily = serif, fontStyle = FontStyle.Italic, fontSize = 17.sp, color = muted)
    val ideaText = TextStyle(fontFamily = serif, fontSize = 20.sp, lineHeight = 27.sp, color = ink)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp, color = ink)
    val tag = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, color = ink)
    val small = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, color = muted)
    val label = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp, color = muted)
}

/** Pocket card surface: [color] fill, 16 dp corners, a barely-there shadow. */
fun Modifier.pocketCard(color: Color = Pocket.card): Modifier =
    shadow(Pocket.cardElevation, Pocket.corner, ambientColor = Pocket.shadow, spotColor = Pocket.shadow)
        .background(color, Pocket.corner)

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pocket.ink, onPrimary = Color.White,
            background = Pocket.background, onBackground = Pocket.ink,
            surface = Pocket.background, onSurface = Pocket.ink,
            surfaceVariant = Pocket.card, onSurfaceVariant = Pocket.ink,
            secondaryContainer = Pocket.card, onSecondaryContainer = Pocket.ink,
        ),
        content = content,
    )
}

/** Small grey uppercase section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Pocket.label, modifier = modifier)
}

package com.yairsarig.pinlink.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pocket Ideas look: white, black, quiet grey labels, 14 dp corners, ideas set in a serif. */
object Pocket {
    val background = Color.White
    val ink = Color.Black
    val muted = Color(0xFF8A8A8A)
    val card = Color(0xFFF3F3F3)
    val pill = Color.White
    val frame = Color(0x1F000000) // rgba(0,0,0,0.12)
    val green = Color(0xFF2EAD5B)
    val amber = Color(0xFFF2A007)
    val grey = Color(0xFFB8B8B8)
    val danger = Color(0xFFC0392B)

    val corner = RoundedCornerShape(14.dp)
    val pillShape = RoundedCornerShape(50)

    /** Georgia isn't on Android; the system serif (Noto Serif) is the closest built-in face. */
    val ideaFont = FontFamily.Serif

    val ideaText = TextStyle(fontFamily = ideaFont, fontSize = 18.sp, lineHeight = 25.sp, color = ink)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp, color = ink)
    val small = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, color = muted)
    val label = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp, color = muted)
}

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

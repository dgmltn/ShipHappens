package com.shiphappens.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.fallbackAccentColor
import com.shiphappens.ui.res.Res
import com.shiphappens.ui.res.hanken_400
import com.shiphappens.ui.res.hanken_500
import com.shiphappens.ui.res.hanken_600
import com.shiphappens.ui.res.hanken_700
import com.shiphappens.ui.res.hanken_800
import com.shiphappens.ui.res.mono_400
import com.shiphappens.ui.res.mono_500
import org.jetbrains.compose.resources.Font

object ShipColors {
    val bg = Color(0xFFF7F6F3)
    val card = Color(0xFFFFFFFF)
    val cardAlt = Color(0xFFFBFAF7)
    val ink = Color(0xFF17150F)
    val muted = Color(0xFF8A857C)
    val faint = Color(0xFFA8A296)
    val hairline = Color(0xFFECEAE3)
    val hairlineStrong = Color(0xFFE2DFD8)
    val segmentBg = Color(0xFFEAE7E0)
    val urgent = Color(0xFFC2410C)
    val delivered = Color(0xFF1F7A4D)
    val deliveredBg = Color(0xFFE7F3EC)
    val toggleOff = Color(0xFFDAD6CE)
}

fun colorFromHex(hex: String?): Color {
    val h = hex?.removePrefix("#") ?: return ShipColors.ink
    val v = h.toLongOrNull(16) ?: return ShipColors.ink
    return Color(0xFF000000 or v)
}

fun Carrier.accentHex(): String = accentColorHex ?: fallbackAccentColor(code)

@Composable
fun hankenFamily() = FontFamily(
    Font(Res.font.hanken_400, FontWeight.Normal),
    Font(Res.font.hanken_500, FontWeight.Medium),
    Font(Res.font.hanken_600, FontWeight.SemiBold),
    Font(Res.font.hanken_700, FontWeight.Bold),
    Font(Res.font.hanken_800, FontWeight.ExtraBold),
)

@Composable
fun monoFamily() = FontFamily(
    Font(Res.font.mono_400, FontWeight.Normal),
    Font(Res.font.mono_500, FontWeight.Medium),
)

@Composable
fun ShipTheme(content: @Composable () -> Unit) {
    val hanken = hankenFamily()
    val base = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = ShipColors.ink,
            background = ShipColors.bg,
            surface = ShipColors.card,
            surfaceVariant = ShipColors.cardAlt,
            onBackground = ShipColors.ink,
            onSurface = ShipColors.ink,
            outline = ShipColors.hairline,
        ),
        typography = Typography(
            displaySmall = base.displaySmall.copy(fontFamily = hanken, fontWeight = FontWeight.ExtraBold),
            headlineMedium = base.headlineMedium.copy(fontFamily = hanken, fontWeight = FontWeight.ExtraBold),
            titleLarge = base.titleLarge.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            bodyLarge = base.bodyLarge.copy(fontFamily = hanken),
            bodyMedium = base.bodyMedium.copy(fontFamily = hanken),
            bodySmall = base.bodySmall.copy(fontFamily = hanken),
            labelLarge = base.labelLarge.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            labelMedium = base.labelMedium.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            labelSmall = base.labelSmall.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}

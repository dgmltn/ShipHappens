package com.dgmltn.shiphappens.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.fallbackAccentColor
import com.dgmltn.shiphappens.design.res.Res
import com.dgmltn.shiphappens.design.res.hanken_400
import com.dgmltn.shiphappens.design.res.hanken_500
import com.dgmltn.shiphappens.design.res.hanken_600
import com.dgmltn.shiphappens.design.res.hanken_700
import com.dgmltn.shiphappens.design.res.hanken_800
import com.dgmltn.shiphappens.design.res.mono_400
import com.dgmltn.shiphappens.design.res.mono_500
import org.jetbrains.compose.resources.Font

object ShipColors {
    val bg = Color(0xFFF7F6F3)
    val card = Color(0xFFFFFFFF)
    val cardAlt = Color(0xFFFBFAF7)
    val ink = Color(0xFF17150F)
    val brand = Color(0xFF1E3A8F)
    val muted = Color(0xFF8A857C)
    val faint = Color(0xFFA8A296)
    val hairline = Color(0xFFECEAE3)
    val hairlineStrong = Color(0xFFE2DFD8)
    val segmentBg = Color(0xFFEAE7E0)
    val urgent = Color(0xFFC2410C)
    val ringAccent = Color(0xFF2563EB)
    val archiveAccent = Color(0xFF2563EB)
    val delivered = Color(0xFF1F7A4D)
    val deliveredBg = Color(0xFFE7F3EC)
    val delayed = Color(0xFFB45309)
    val delayedBg = Color(0xFFFBF0DE)
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

@Composable
fun ShipPreview(
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    ShipTheme {
        Surface {
            Box(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}
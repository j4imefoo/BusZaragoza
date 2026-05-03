package org.baumweg.buszaragoza.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandRed = Color(0xFFDD271B)
private val LightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = BrandRed,
    onPrimaryContainer = Color.White,
    secondary = BrandRed,
    onSecondary = Color.White,
    secondaryContainer = BrandRed,
    onSecondaryContainer = Color.White,
    tertiary = BrandRed,
)
private val DarkColors = darkColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = BrandRed,
    onPrimaryContainer = Color.White,
    secondary = BrandRed,
    onSecondary = Color.White,
    secondaryContainer = BrandRed,
    onSecondaryContainer = Color.White,
    tertiary = BrandRed,
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

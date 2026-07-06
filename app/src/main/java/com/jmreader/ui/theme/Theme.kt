package com.jmreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    primary = md_primary,
    onPrimary = md_on_primary,
    primaryContainer = md_primary_container,
    onPrimaryContainer = md_on_primary_container,
    secondary = md_secondary,
    onSecondary = md_on_secondary,
    background = md_background,
    onBackground = md_on_background,
    surface = md_surface,
    onSurface = md_on_surface,
    surfaceVariant = md_surface_variant,
    onSurfaceVariant = md_on_surface_variant,
    outline = md_outline,
    error = error,
    onError = onError,
)

private val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_on_primary,
    primaryContainer = md_dark_primary_container,
    onPrimaryContainer = md_dark_on_primary_container,
    secondary = md_secondary,
    onSecondary = md_on_secondary,
    background = md_dark_background,
    onBackground = md_dark_on_background,
    surface = md_dark_surface,
    onSurface = md_dark_on_surface,
    surfaceVariant = md_dark_surface_variant,
    onSurfaceVariant = md_dark_on_surface_variant,
    outline = md_dark_outline,
    error = error,
    onError = onError,
)

@Composable
fun JMTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = JMTypography,
        content = content,
    )
}

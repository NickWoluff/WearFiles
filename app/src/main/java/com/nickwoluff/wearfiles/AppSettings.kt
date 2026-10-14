package com.nickwoluff.wearfiles

import android.content.Context
import android.annotation.SuppressLint
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import com.google.android.material.color.utilities.*

enum class PaletteStyle(val label: String) {
    TONAL_SPOT("Tonal Spot"), VIBRANT("Vibrant"), EXPRESSIVE("Expressive"),
    NEUTRAL("Neutral"), MONOCHROME("Monochrome"), FIDELITY("Fidelity")
}

enum class ScreenLayout(val label: String) { SQUARE("方屏"), ROUND("圆屏") }
data class Accent(val label: String, val argb: Int)
val accents = listOf(Accent("紫色", 0xFF6750A4.toInt()), Accent("蓝色", 0xFF0061A4.toInt()),
    Accent("青色", 0xFF006A6A.toInt()), Accent("绿色", 0xFF386A20.toInt()),
    Accent("橙色", 0xFFA04100.toInt()), Accent("粉色", 0xFF984061.toInt()))
data class AppSettings(val accent: Int = accents.first().argb,
    val palette: PaletteStyle = PaletteStyle.TONAL_SPOT, val scale: Int = 100,
    val layout: ScreenLayout = ScreenLayout.ROUND)
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    // 首次识别后保存结果，后续启动尊重用户选择，不再用系统报告覆盖。
    private val initialLayout = ScreenLayout.entries.firstOrNull {
        it.name == prefs.getString("screen_layout", null)
    } ?: (if (context.resources.configuration.isScreenRound) ScreenLayout.ROUND else ScreenLayout.SQUARE)
        .also { prefs.edit().putString("screen_layout", it.name).apply() }
    fun read() = AppSettings(prefs.getInt("accent", accents.first().argb),
        PaletteStyle.entries.firstOrNull { it.name == prefs.getString("palette", "") } ?: PaletteStyle.TONAL_SPOT,
        prefs.getInt("scale", 100).coerceIn(80, 130),
        ScreenLayout.entries.firstOrNull { it.name == prefs.getString("screen_layout", null) } ?: initialLayout)
    fun write(settings: AppSettings) {
        prefs.edit().putInt("accent", settings.accent).putString("palette", settings.palette.name)
            .putInt("scale", settings.scale).putString("screen_layout", settings.layout.name).apply()
    }
    fun skippedStorageGuide(): Boolean = prefs.getBoolean("storage_guide_skipped", false)
    fun skipStorageGuide() { prefs.edit().putBoolean("storage_guide_skipped", true).apply() }
    fun readSpace(): StorageSpace = StorageSpace(prefs.getLong("space_available", 0), prefs.getLong("space_total", 0))
    fun writeSpace(space: StorageSpace) {
        if (space.total > 0) prefs.edit().putLong("space_available", space.available)
            .putLong("space_total", space.total).apply()
    }
}

internal fun adbPermissionCommands(packageName: String): String = listOf(
    "adb shell pm grant $packageName android.permission.READ_EXTERNAL_STORAGE\n",
    "adb shell pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE\n",
    "adb shell appops set $packageName MANAGE_EXTERNAL_STORAGE allow"
).joinToString("\n")
/** 使用 Google Material Color Utilities 的官方动态调色板生成主题。 */
// Material 将颜色工具标记为库内部 API；集中在此适配，依赖固定为 1.13.0。
@SuppressLint("RestrictedApi")
fun watchColors(settings: AppSettings): ColorScheme {
    val seed = Hct.fromInt(settings.accent)
    val scheme: DynamicScheme = when (settings.palette) {
        PaletteStyle.TONAL_SPOT -> SchemeTonalSpot(seed, true, 0.0)
        PaletteStyle.VIBRANT -> SchemeVibrant(seed, true, 0.0)
        PaletteStyle.EXPRESSIVE -> SchemeExpressive(seed, true, 0.0)
        PaletteStyle.NEUTRAL -> SchemeNeutral(seed, true, 0.0)
        PaletteStyle.MONOCHROME -> SchemeMonochrome(seed, true, 0.0)
        PaletteStyle.FIDELITY -> SchemeFidelity(seed, true, 0.0)
    }
    return ColorScheme(
        primary = Color(scheme.primary), primaryDim = Color(scheme.primaryPalette.tone(70)),
        primaryContainer = Color(scheme.primaryContainer), onPrimary = Color(scheme.onPrimary),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        secondary = Color(scheme.secondary), secondaryDim = Color(scheme.secondaryPalette.tone(70)),
        secondaryContainer = Color(scheme.secondaryContainer), onSecondary = Color(scheme.onSecondary),
        onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary), tertiaryDim = Color(scheme.tertiaryPalette.tone(70)),
        tertiaryContainer = Color(scheme.tertiaryContainer), onTertiary = Color(scheme.onTertiary),
        onTertiaryContainer = Color(scheme.onTertiaryContainer),
        surfaceContainerLow = Color(scheme.surfaceContainerLow), surfaceContainer = Color(scheme.surfaceContainer),
        surfaceContainerHigh = Color(scheme.surfaceContainerHigh), onSurface = Color(scheme.onSurface),
        onSurfaceVariant = Color(scheme.onSurfaceVariant), outline = Color(scheme.outline),
        outlineVariant = Color(scheme.outlineVariant), background = Color.Black, onBackground = Color(scheme.onBackground),
        error = Color(scheme.error), errorDim = Color(scheme.errorPalette.tone(70)),
        errorContainer = Color(scheme.errorContainer), onError = Color(scheme.onError),
        onErrorContainer = Color(scheme.onErrorContainer))
}

package me.zssu.ime.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding

@Immutable
internal data class GlassPalette(
    val dark: Boolean,
    val background: Color,
    val ambientA: Color,
    val ambientB: Color,
    val ambientC: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val contentSurface: Color,
    val contentSurfaceAlt: Color,
    val contentStroke: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val iconChip: Color,
    val inactive: Color,
    val warm: Color,
    val warmContainer: Color,
    val ok: Color,
)

private val LightGlassPalette = GlassPalette(
    dark = false,
    background = Color(0xFFF4F1EA),
    ambientA = Color(0x142F7D68),
    ambientB = Color(0x12D77A5A),
    ambientC = Color(0x0F5A6FA8),
    glassTop = Color(0xE6FFFFFF),
    glassBottom = Color(0xB8F8F5EF),
    glassStroke = Color(0xE6FFFFFF),
    glassHighlight = Color(0xCCFFFFFF),
    contentSurface = Color(0xFFFFFFFF),
    contentSurfaceAlt = Color(0xFFF9F7F2),
    contentStroke = Color(0x14000000),
    textPrimary = Color(0xFF1C1B18),
    textSecondary = Color(0xFF5F5B53),
    textTertiary = Color(0xFF8A847A),
    accent = Color(0xFF2F6B58),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFDDEFE7),
    iconChip = Color(0xFFEAF6F1),
    inactive = Color(0xFF726D64),
    warm = Color(0xFFB4522E),
    warmContainer = Color(0xFFF6DED3),
    ok = Color(0xFF2F6B58),
)

private val DarkGlassPalette = GlassPalette(
    dark = true,
    background = Color(0xFF0E100F),
    ambientA = Color(0x332F7D68),
    ambientB = Color(0x2ED77A5A),
    ambientC = Color(0x245A6FA8),
    glassTop = Color(0xCC1A1D1B),
    glassBottom = Color(0x99131614),
    glassStroke = Color(0x38FFFFFF),
    glassHighlight = Color(0x66FFFFFF),
    contentSurface = Color(0xFF1A1D1B),
    contentSurfaceAlt = Color(0xFF202421),
    contentStroke = Color(0x1FFFFFFF),
    textPrimary = Color(0xFFF2EFE8),
    textSecondary = Color(0xFFBDB8AE),
    textTertiary = Color(0xFF8C877D),
    accent = Color(0xFF8FD6BC),
    onAccent = Color(0xFF062018),
    accentContainer = Color(0xFF1E3A30),
    iconChip = Color(0xFF203A31),
    inactive = Color(0xFFA9A49B),
    warm = Color(0xFFF0A17E),
    warmContainer = Color(0xFF4A2A1E),
    ok = Color(0xFF8FD6BC),
)

internal fun glassPalette(dark: Boolean): GlassPalette = if (dark) DarkGlassPalette else LightGlassPalette

internal val LocalGlassPalette = staticCompositionLocalOf { LightGlassPalette }
internal val LocalGlassEffects = staticCompositionLocalOf { true }

@Composable
internal fun SettingsGlassTheme(
    dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = remember(dark) { glassPalette(dark) }
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentContainer,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.warm,
            onSecondary = Color(0xFF2A1008),
            secondaryContainer = palette.warmContainer,
            onSecondaryContainer = palette.textPrimary,
            tertiary = palette.accent,
            onTertiary = palette.onAccent,
            tertiaryContainer = palette.accentContainer,
            onTertiaryContainer = palette.textPrimary,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.contentSurface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.contentSurfaceAlt,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.contentStroke,
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentContainer,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.warm,
            onSecondary = Color.White,
            secondaryContainer = palette.warmContainer,
            onSecondaryContainer = palette.textPrimary,
            tertiary = palette.accent,
            onTertiary = palette.onAccent,
            tertiaryContainer = palette.accentContainer,
            onTertiaryContainer = palette.textPrimary,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.contentSurface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.contentSurfaceAlt,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.contentStroke,
            error = Color(0xFFB3261E),
            onError = Color.White,
        )
    }
    val base = Typography()
    val typography = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.9.sp),
    )
    M3MaterialTheme(colorScheme = scheme, typography = typography) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGlassPalette provides palette, content = content)
    }
}

/**
 * A translucent navigation-layer surface. Content cards use [GlassSettingRow] instead;
 * glass is deliberately not stacked on glass.
 */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalGlassPalette.current
    val glass = LocalGlassEffects.current
    val base = if (glass) {
        Brush.linearGradient(listOf(palette.glassTop, palette.glassBottom))
    } else {
        SolidColor(palette.contentSurface)
    }
    Box(
        modifier = modifier
            .shadow(if (glass) 18.dp else 2.dp, shape)
            .clip(shape)
            .background(base)
            .then(if (tint != Color.Transparent) Modifier.background(tint, shape) else Modifier)
            .border(1.dp, if (glass) palette.glassStroke else palette.contentStroke, shape)
            .drawWithContent {
                drawContent()
                if (glass) {
                    val inset = 14.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, palette.glassHighlight, Color.Transparent),
                        ),
                        start = Offset(inset, 1.dp.toPx()),
                        end = Offset(size.width - inset, 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            },
        content = content,
    )
}

@Composable
internal fun AmbientBackground(modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    Canvas(modifier.fillMaxSize()) {
        drawRect(palette.background)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.ambientA, Color.Transparent),
                center = Offset(size.width * 0.15f, size.height * 0.04f),
                radius = size.minDimension * 0.85f,
            ),
            radius = size.minDimension * 0.85f,
            center = Offset(size.width * 0.15f, size.height * 0.04f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.ambientB, Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.38f),
                radius = size.minDimension * 0.7f,
            ),
            radius = size.minDimension * 0.7f,
            center = Offset(size.width * 0.92f, size.height * 0.38f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.ambientC, Color.Transparent),
                center = Offset(size.width * 0.2f, size.height * 0.92f),
                radius = size.minDimension * 0.65f,
            ),
            radius = size.minDimension * 0.65f,
            center = Offset(size.width * 0.2f, size.height * 0.92f),
        )
    }
}

@Composable
internal fun GlassTopBar(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    glassEnabled: Boolean,
    onToggleGlass: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        GlassSurface(Modifier.fillMaxWidth(), RoundedCornerShape(26.dp)) {
            if (searchOpen) {
                SearchRow(query, onQueryChange, onCloseSearch, palette)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "KOMOREBI IME",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textTertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "設定",
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.textPrimary,
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "設定を検索", tint = palette.textPrimary)
                    }
                    IconButton(onClick = onToggleGlass) {
                        Icon(
                            Icons.Rounded.Contrast,
                            contentDescription = if (glassEnabled) "ガラス表現を減らす" else "ガラス表現を有効にする",
                            tint = if (glassEnabled) palette.accent else palette.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    palette: GlassPalette,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(21.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = palette.textPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("設定を検索", color = palette.textTertiary, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
        IconButton(onClick = {
            focusManager.clearFocus()
            onClose()
        }) {
            Icon(Icons.Rounded.Close, contentDescription = "検索を閉じる", tint = palette.textSecondary)
        }
    }
}

@Composable
internal fun GlassQuickBar(
    onKeyboard: () -> Unit,
    onInputMethod: () -> Unit,
    onSearch: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier.fillMaxWidth(), RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier.padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            QuickAction(Icons.Rounded.Keyboard, "キーボード", onKeyboard, Modifier.weight(1f))
            QuickAction(Icons.Rounded.Translate, "入力方法", onInputMethod, Modifier.weight(1f))
            QuickAction(Icons.Rounded.Search, "検索", onSearch, Modifier.weight(1f))
            QuickAction(Icons.Rounded.Folder, "バックアップ", onBackup, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(19.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            maxLines = 1,
            fontSize = 9.sp,
        )
    }
}

@Composable
internal fun GlassBottomDock(
    selectedTab: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier.fillMaxWidth(), RoundedCornerShape(30.dp)) {
        Row(
            modifier = Modifier.padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsTab.entries.forEach { tab ->
                DockItem(
                    tab = tab,
                    selected = tab == selectedTab,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    tab: SettingsTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val background by animateColorAsState(
        targetValue = if (selected) palette.accent else Color.Transparent,
        label = "dock-bg",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) palette.onAccent else palette.inactive,
        label = "dock-fg",
    )
    Column(
        modifier = modifier
            .height(53.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(background)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            color = foreground,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private val SettingsTab.icon: ImageVector
    get() = when (this) {
        SettingsTab.HOME -> Icons.Rounded.Home
        SettingsTab.INPUT -> Icons.Rounded.Keyboard
        SettingsTab.CONVERT -> Icons.Rounded.Translate
        SettingsTab.DATA -> Icons.Rounded.Folder
        SettingsTab.INFO -> Icons.Rounded.Info
    }

@Composable
internal fun StatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val palette = LocalGlassPalette.current
    val dot = when {
        warn -> palette.warm
        active -> palette.ok
        else -> palette.textTertiary
    }
    val container = when {
        warn -> palette.warmContainer
        active -> palette.accentContainer
        else -> palette.contentSurface
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = palette.textPrimary,
        border = BorderStroke(1.dp, if (warn) palette.warm.copy(alpha = 0.35f) else palette.contentStroke),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(dot, CircleShape),
            )
            Text(text, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
        }
    }
}

@Composable
internal fun GlassSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    count: Int? = null,
) {
    val palette = LocalGlassPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary,
            )
        }
        if (count != null) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
            )
        }
    }
}

@Composable
internal fun GlassSettingRow(
    entry: SettingsEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    category: String? = null,
) {
    val palette = LocalGlassPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = palette.contentSurface,
        contentColor = palette.textPrimary,
        border = BorderStroke(1.dp, palette.contentStroke),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.iconChip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                if (category != null) {
                    Text(
                        category,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                    )
                }
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.textTertiary.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun GlassCategoryChip(
    tab: SettingsTab,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = palette.contentSurface,
        contentColor = palette.textPrimary,
        border = BorderStroke(1.dp, palette.contentStroke),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(tab.icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(17.dp))
            Text(tab.label, style = MaterialTheme.typography.labelMedium, color = palette.textPrimary)
        }
    }
}

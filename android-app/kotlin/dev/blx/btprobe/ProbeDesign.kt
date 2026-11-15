package dev.blx.btprobe

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.*
import kotlinx.coroutines.delay

internal data class GlassOptions(val dark: Boolean, val solid: Boolean, val reducedMotion: Boolean, val highContrast: Boolean)
internal val LocalGlass = staticCompositionLocalOf { GlassOptions(false, false, false, false) }
internal val Accent = Color(0xFF087F78)
internal val NightAccent = Color(0xFF72E0D2)
internal val Coral = Color(0xFFC04C2E)
internal val Lilac = Color(0xFF7060BD)
internal fun Context.options(): SharedPreferences = getSharedPreferences("appearance", Context.MODE_PRIVATE)

@Composable internal fun ProbeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.options() }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Suppress("UNUSED_VARIABLE") val observe = revision
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.getString("theme", "system")) { "dark" -> true; "light" -> false; else -> systemDark }
    val systemContrast = runCatching { Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) != 0 }.getOrDefault(false)
    val contrast = prefs.getBoolean("contrast", false) || systemContrast
    val solid = prefs.getBoolean("solid", false) || contrast || Build.VERSION.SDK_INT < 31
    val reduced = prefs.getBoolean("reduceMotion", false) || !ValueAnimator.areAnimatorsEnabled()
    val colors = if (dark) darkColorScheme(
        primary = NightAccent, onPrimary = Color(0xFF082B28), primaryContainer = Color(0xFF174B44), onPrimaryContainer = Color(0xFFB7F4E8), background = Color(0xFF0C111B),
        surface = Color(0xFF19222E), onSurface = Color(0xFFF3F6FA), onBackground = Color(0xFFF3F6FA),
        surfaceVariant = Color(0xFF253241), onSurfaceVariant = if (contrast) Color.White else Color(0xFFB4C1CF),
        outline = Color(0xFF637889), error = Color(0xFFFFB4AB)
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFFDCEFEA), onPrimaryContainer = Color(0xFF073C37), background = Color(0xFFF3F5F8),
        surface = Color.White, onSurface = Color(0xFF152331), onBackground = Color(0xFF152331),
        surfaceVariant = Color(0xFFE6EDF1), onSurfaceVariant = if (contrast) Color(0xFF223340) else Color(0xFF536575),
        outline = Color(0xFF6D8192), error = Color(0xFFA82C25)
    )
    val typography = Typography(
        displaySmall = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-1.2).sp),
        headlineLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 37.sp, letterSpacing = (-.8).sp),
        headlineMedium = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 30.sp, letterSpacing = (-.5).sp),
        titleLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-.3).sp),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    )
    SideEffect {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
    }
    CompositionLocalProvider(LocalGlass provides GlassOptions(dark, solid, reduced, contrast)) {
        MaterialTheme(colorScheme = colors, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}

/** Poll only while a visible lifecycle is STARTED, with service notifications for prompt updates. */
@Composable internal fun meshRevision(): Int {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val listener = Runnable { revision++ }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) MeshService.addListener(listener)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) MeshService.removeListener(listener)
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) MeshService.addListener(listener)
        onDispose { owner.lifecycle.removeObserver(observer); MeshService.removeListener(listener) }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { revision++; delay(500) }
        }
    }
    return revision
}

@Composable internal fun GlassScreen(
    activity: ComponentActivity, selected: Int, title: String, subtitle: String,
    back: Boolean = true, navigate: ((Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val haze = remember { HazeState() }
    val scroll = rememberSaveable(selected, saver = ScrollState.Saver) { ScrollState(0) }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val dockClearance = (112 + 60 * (density.fontScale - 1).coerceAtLeast(0f)).dp
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // The ambient source fills the window; controls scroll in a safe viewport above the dock.
        Box(Modifier.matchParentSize().hazeSource(haze).background(Brush.verticalGradient(listOf(colors.background, colors.surfaceVariant.copy(alpha = .32f)))))
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxSize().align(Alignment.TopCenter).imePadding().hazeSource(haze)
                .padding(bottom = if (keyboardVisible) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + dockClearance)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .verticalScroll(scroll)
                .padding(horizontal = 22.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (back) IconButton(onClick = { activity.onBackPressedDispatcher.onBackPressed() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                else SignalMark(Modifier.size(36.dp), colors.primary)
                Text("BT Probe", Modifier.weight(1f).padding(start = if (back) 0.dp else 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (activity !is PowerSettingsActivity) IconButton(onClick = { activity.startActivityForResult(Intent(activity, PowerSettingsActivity::class.java), 30) }) { Icon(Icons.Default.Settings, "Settings") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            content()
        }
        if (!keyboardVisible) FloatingDock(haze, selected, Modifier.align(Alignment.BottomCenter)) { destination ->
            if (navigate != null) navigate(destination) else navigateTo(activity, destination)
        }
    }
}

internal fun navigateTo(activity: ComponentActivity, index: Int) {
    val clazz = when (index) { 1 -> ChatActivity::class.java; 2 -> QuakeActivity::class.java; else -> MainActivity::class.java }
    if (activity.javaClass == clazz && index != 3) return
    val intent = Intent(activity, clazz)
    if (index == 3) intent.putExtra("page", "tools")
    if (clazz == MainActivity::class.java) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    activity.startActivity(intent)
    if (activity !is MainActivity && clazz != MainActivity::class.java) activity.finish()
}

@Composable private fun FloatingDock(haze: HazeState, selected: Int, modifier: Modifier, navigate: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val options = LocalGlass.current
    val shape = RoundedCornerShape(30.dp)
    val base = modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)
        .widthIn(max = 520.dp).fillMaxWidth().shadow(18.dp, shape, spotColor = Color.Black.copy(alpha = .17f))
        .clip(shape)
    val material = if (options.solid) base.background(colors.surface) else base.hazeEffect(haze) {
        backgroundColor = colors.surface
        tints = listOf(HazeTint(colors.surface.copy(alpha = .90f)))
        blurRadius = 24.dp; noiseFactor = .025f
    }
    Row(material.border(1.dp, colors.outline.copy(alpha = if (options.highContrast) .7f else .16f), shape).padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("Network", "Chat", "Sensing", "Tools").forEachIndexed { i, label ->
            val selectedInk = if (options.dark) colors.primary else Color(0xFF075C57)
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val size by animateFloatAsState(if (pressed) .95f else 1f, if (options.reducedMotion) snap() else spring(dampingRatio = 1f, stiffness = 650f), label = "dock press")
            Column(Modifier.weight(1f).scale(size).clip(RoundedCornerShape(24.dp))
                .background(if (selected == i) colors.primary.copy(alpha = if (options.dark) .16f else .10f) else Color.Transparent)
                .selectable(selected == i, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { navigate(i) })
                .padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (i) {
                    0 -> SignalMark(Modifier.size(22.dp), if (selected == i) selectedInk else colors.onSurfaceVariant)
                    1 -> Icon(Icons.Default.Email, null, Modifier.size(22.dp), tint = if (selected == i) selectedInk else colors.onSurfaceVariant)
                    2 -> WaveMark(Modifier.size(22.dp), if (selected == i) selectedInk else colors.onSurfaceVariant)
                    else -> Icon(Icons.Default.MoreVert, null, Modifier.size(22.dp), tint = if (selected == i) selectedInk else colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(3.dp))
                Text(label, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = if (selected == i) selectedInk else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.surface)
        .border(1.dp, colors.outline.copy(alpha = if (LocalGlass.current.highContrast) .7f else .09f), RoundedCornerShape(26.dp))
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable internal fun Eyebrow(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun Supporting(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun Pill(text: String, active: Boolean = true) {
    val c = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val ink = if (active && !LocalGlass.current.dark) Color(0xFF075C57) else c
    Row(Modifier.clip(CircleShape).background(c.copy(alpha = .12f)).padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(c, CircleShape)); Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = ink)
    }
}
@Composable internal fun PremiumButton(text: String, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val options = LocalGlass.current
    val scale by animateFloatAsState(if (pressed) .98f else 1f, if (options.reducedMotion) snap() else spring(dampingRatio = 1f, stiffness = 700f), label = "button press")
    Button(onClick, modifier = Modifier.fillMaxWidth().scale(scale).heightIn(min = 52.dp), enabled = enabled,
        shape = RoundedCornerShape(17.dp), interactionSource = source,
        colors = if (secondary) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) else ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
@Composable internal fun ActionRow(title: String, detail: String, icon: ImageVector = Icons.AutoMirrored.Filled.ArrowForward, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Supporting(detail) }
        Switch(checked, onCheckedChange = null)
    }
}
@Composable internal fun SignalMark(modifier: Modifier, color: Color) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val s = size.minDimension; val center = Offset(size.width / 2, size.height * .7f)
        drawCircle(color, s * .08f, center)
        for (r in listOf(.28f, .46f)) drawArc(color, 215f, 110f, false, Offset(center.x - s*r, center.y - s*r), androidx.compose.ui.geometry.Size(s*r*2,s*r*2), style = Stroke(s*.055f, cap = StrokeCap.Round))
    }
}
@Composable internal fun WaveMark(modifier: Modifier, color: Color) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val p = Path(); p.moveTo(0f,size.height*.5f)
        listOf(.23f to .5f,.35f to .2f,.48f to .85f,.62f to .1f,.76f to .5f,1f to .5f).forEach { p.lineTo(size.width*it.first,size.height*it.second) }
        drawPath(p,color,style=Stroke(2.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
    }
}

@Composable internal fun MetricPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second() }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { first() }; Box(Modifier.weight(1f)) { second() }
    }
}

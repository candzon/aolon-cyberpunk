package com.feri.aolon_cyberpunk.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Native tab frame: a large title that collapses on scroll, then a lazy list.
 * Insets are zeroed because the outer scaffold already owns the bottom bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScaffold(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    refreshing: Boolean? = null,
    onRefresh: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Neon.Void,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = navigationIcon,
                actions = { actions() },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Neon.Void,
                    scrolledContainerColor = Neon.Panel,
                ),
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        val list = @Composable {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        Box(Modifier.padding(padding)) {
            if (refreshing == null) list() else PullToRefreshBox(refreshing, onRefresh) { list() }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Neon.Panel),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Neon.Soft) }
            }
            content()
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Neon.Red else Neon.Purple, contentColor = Neon.Void),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Neon.White) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = ControlShape) {
        Text(text, color = color)
    }
}

/** Settings-style row; the whole row is the tap target when it has an action. */
@Composable
fun SettingRow(
    headline: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Neon.Soft) }
        }
        trailing?.invoke()
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = Neon.Line.copy(alpha = 0.3f))

/** Empty, offline and error states all say what happened and offer the one action that fixes it. */
@Composable
fun StateMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
        action?.let { PrimaryButton(it, onAction) }
    }
}

/** Steps against goal; the sweep animates only when a new value arrives so a sync is visible. */
@Composable
fun StepRing(steps: Int, goal: Int?, modifier: Modifier = Modifier) {
    val target = if (goal == null) 0f else (steps.toFloat() / goal).coerceIn(0f, 1f)
    val sweep by animateFloatAsState(target, tween(if (rememberReducedMotion()) 0 else 700), label = "ring")
    Box(modifier.size(196.dp).semantics { contentDescription = "$steps steps" + (goal?.let { " of $it" } ?: "") }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Neon.Line.copy(alpha = 0.5f), 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            if (sweep > 0f) drawArc(Neon.Purple, 135f, 270f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(grouped(steps), style = Telemetry.copy(fontSize = 34.sp))
            Text(goal?.let { "of ${grouped(it)} steps" } ?: "steps today", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun HourlyBars(steps: Map<Int, Int>) {
    val peak = (steps.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "Steps per hour chart" }) {
        val slot = size.width / 24f
        val bar = slot * 0.6f
        for (hour in 0 until 24) {
            val value = steps[hour] ?: 0
            val h = if (value == 0) 2.dp.toPx() else size.height * value / peak
            drawRect(
                if (value == 0) Neon.Line.copy(alpha = 0.5f) else Neon.Purple,
                Offset(hour * slot + (slot - bar) / 2, size.height - h),
                Size(bar, h),
            )
        }
    }
    AxisLabels(listOf("00", "06", "12", "18", "23"))
}

/** Bars meeting the goal switch to blue so a good day reads without the axis; tapping a bar picks that day. */
@Composable
fun WeekBars(days: Map<LocalDate, Int>, goal: Int?, selected: LocalDate? = null, onPick: (LocalDate) -> Unit = {}) {
    val peak = maxOf(days.values.maxOrNull() ?: 0, goal ?: 0, 1)
    val dates = days.keys.toList()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .pointerInput(dates) { detectTapGestures { onPick(dates[(it.x / size.width * dates.size).toInt().coerceIn(dates.indices)]) } }
            .semantics { contentDescription = "Steps per day chart" },
    ) {
        val slot = size.width / days.size
        val bar = slot * 0.5f
        if (selected != null && selected in days) {
            drawRect(Neon.PanelLight, Offset(dates.indexOf(selected) * slot, 0f), Size(slot, size.height))
        }
        days.values.forEachIndexed { i, value ->
            val h = if (value == 0) 2.dp.toPx() else size.height * value / peak
            val color = when {
                value == 0 -> Neon.Line.copy(alpha = 0.5f)
                goal != null && value >= goal -> Neon.Blue
                else -> Neon.Purple
            }
            drawRect(color, Offset(i * slot + (slot - bar) / 2, size.height - h), Size(bar, h))
        }
        goal?.let {
            val y = size.height * (1 - it.toFloat() / peak)
            drawLine(Neon.Blue.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
    }
    AxisLabels(days.keys.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) }, centered = true)
}

/** Steps through days or weeks; the arrows stop at the first record and at today. */
@Composable
fun PeriodNavigator(label: String, canBack: Boolean, canForward: Boolean, unit: String, onBack: () -> Unit, onForward: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous $unit") }
        Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
        IconButton(onForward, enabled = canForward) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next $unit") }
    }
}

@Composable
private fun AxisLabels(labels: List<String>, centered: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        labels.forEach {
            Text(
                it,
                color = Neon.Soft,
                style = MaterialTheme.typography.labelSmall,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

fun grouped(n: Int): String = java.text.NumberFormat.getIntegerInstance(Locale.US).format(n)

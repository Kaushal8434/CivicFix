package com.civicfix.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.civicfix.app.data.Category
import com.civicfix.app.data.Complaint
import com.civicfix.app.data.ReferenceData
import com.civicfix.app.data.Status
import com.civicfix.app.ui.theme.Amber
import com.civicfix.app.ui.theme.Danger
import com.civicfix.app.ui.theme.HeroGradient
import com.civicfix.app.ui.theme.Info
import com.civicfix.app.ui.theme.Ok
import com.civicfix.app.ui.theme.categoryColor
import com.civicfix.app.domain.timeLeft
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(Modifier.background(HeroGradient)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
            }
        },
        floatingActionButton = floating,
        bottomBar = bottomBar,
        content = content,
    )
}

/** White rounded card with an optional emoji badge + title. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    icon: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        EmojiBadge(icon, accent, 34)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            content()
        }
    }
}

@Composable
fun EmojiBadge(emoji: String, color: Color, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = (sizeDp * 0.48).sp) }
}

/** Large, rounded, full-width primary action. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = if (color != null) ButtonDefaults.buttonColors(containerColor = color) else ButtonDefaults.buttonColors(),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
    ) { Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

fun statusColor(status: Status, overdue: Boolean): Color = when {
    overdue -> Danger
    status == Status.NEW -> Info
    status == Status.ASSIGNED || status == Status.IN_PROGRESS -> Amber
    status == Status.REOPENED -> Danger
    else -> Ok
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun StatusPill(c: Complaint, now: Long) {
    val overdue = c.isOverdue(now)
    val label = when (c.status) {
        Status.RESOLVED -> "Resolved – verify"
        Status.CLOSED -> "Closed"
        Status.REOPENED -> "Reopened"
        else -> c.status.label
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(label, statusColor(c.status, false))
        if (overdue) Pill("Overdue", Danger)
        if (c.escalationLevel >= 2) Pill("Escalated L${c.escalationLevel}", Danger)
        else if (c.escalationLevel == 1 && c.status.isOpen) Pill("Warned", Amber)
    }
}

@Composable
fun ComplaintCard(c: Complaint, ref: ReferenceData, now: Long, onClick: () -> Unit) {
    val cat = ref.category(c.category)
    val accent = categoryColor(cat.key)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(c.beforePhoto, cat.emoji, 76, accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cat.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("📍 " + c.address.ifBlank { c.locationLabel }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(c, now)
                Text(
                    "${c.id} · ${timeLeft(c.dueAt, now).takeIf { c.status.isOpen } ?: "done"}" + if (c.supportCount > 0) " · 👥 +${c.supportCount}" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun PhotoThumb(path: String?, fallbackEmoji: String, sizeDp: Int, accent: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val model = imageModel(path)
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(fallbackEmoji, fontSize = (sizeDp / 2.4).sp)
        }
    }
}

@Composable
fun PhotoLarge(path: String?, label: String, heightDp: Int = 220) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth().height(heightDp.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val model = imageModel(path)
            if (model != null) {
                AsyncImage(model = model, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("No photo", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A server URL, an existing local file, or null. */
fun imageModel(path: String?): Any? = when {
    path == null -> null
    path.startsWith("http") -> path
    File(path).exists() -> File(path)
    else -> null
}

/** Dropdown selector built from an outlined field-like button + DropdownMenu. */
@Composable
fun <T> Selector(label: String, options: List<T>, selected: T?, name: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val active = enabled && options.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(
                onClick = { open = true }, enabled = active,
                shape = RoundedCornerShape(14.dp),
                color = if (active) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, if (selected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selected?.let(name) ?: "Select…", modifier = Modifier.weight(1f),
                        color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected != null) FontWeight.Medium else FontWeight.Normal,
                    )
                    Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(name(o)) }, onClick = { onSelect(o); open = false })
                }
            }
        }
    }
}

@Composable
fun SlaBar(progress: Float, overdue: Boolean) {
    val color = when {
        overdue -> Danger
        progress > 0.75f -> Amber
        else -> Ok
    }
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), label = "sla")
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(p).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
    }
}

@Composable
fun BarRow(label: String, value: Float, max: Float, valueText: String, color: Color = MaterialTheme.colorScheme.primary) {
    val fraction by animateFloatAsState(if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f), label = "bar")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(valueText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.14f))) {
            Box(Modifier.fillMaxWidth(fraction).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
    }
}

/** Selectable category tile used in the report wizard. */
@Composable
fun CategoryTile(c: Category, selected: Boolean, modifier: Modifier = Modifier, aiHint: Float? = null, onClick: () -> Unit) {
    val accent = categoryColor(c.key)
    val border by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.outlineVariant, label = "tile")
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = 96.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Box {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EmojiBadge(c.emoji, accent, 36)
                Text(c.label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (aiHint != null && aiHint >= 0.05f) Text("AI ${(aiHint * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = accent)
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

/** Numbered progress indicator for multi-step flows. */
@Composable
fun StepIndicator(steps: List<String>, current: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { i, s ->
            val done = i < current
            val active = i == current
            val color = if (done || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = if (i == 0) Color.Transparent else if (done || active) color else MaterialTheme.colorScheme.outlineVariant)
                    Box(
                        Modifier.size(30.dp).clip(CircleShape)
                            .background(if (done || active) color else MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(2.dp, color, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        else Text("${i + 1}", color = if (active) Color.White else color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = if (i == steps.lastIndex) Color.Transparent else if (done) color else MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(s, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** Compact KPI tile. */
@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier, emoji: String? = null) {
    Card(
        modifier, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth()) {
            if (emoji != null) Text(emoji, fontSize = 18.sp)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** One AI prediction row: emoji, label, animated confidence bar. */
@Composable
fun ConfidenceRow(c: Category, p: Float, highlight: Boolean) {
    val accent = categoryColor(c.key)
    val fraction by animateFloatAsState(p.coerceIn(0f, 1f), label = "conf")
    Row(verticalAlignment = Alignment.CenterVertically) {
        EmojiBadge(c.emoji, accent, 32)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(c.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = accent)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.14f))) {
                Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(RoundedCornerShape(4.dp)).background(accent))
            }
        }
    }
}

/** Small tinted info banner. */
@Composable
fun Banner(text: String, color: Color, emoji: String? = null) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) {
                Text(emoji, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

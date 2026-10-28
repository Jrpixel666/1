package com.omarchy.launcher.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarchy.launcher.ui.theme.OmarchyPalette

/** Renders a parsed [OwlWidget] with Compose, substituting live values and reporting taps. */
@Composable
fun RenderOwlWidget(widget: OwlWidget, values: Map<String, String>, onAction: (WidgetAction) -> Unit) {
    RenderNode(widget.root, values, onAction)
}

@Composable
private fun RenderNode(node: OwlNode, values: Map<String, String>, onAction: (WidgetAction) -> Unit) {
    when (node) {
        is OwlNode.Column -> Column(
            modifier = commonModifier(node.props),
            verticalArrangement = spacingArrangementVertical(node.props),
        ) {
            node.children.forEach { RenderInColumn(it, values, onAction) }
        }
        is OwlNode.Row -> Row(
            modifier = commonModifier(node.props),
            horizontalArrangement = spacingArrangementHorizontal(node.props),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { RenderInRow(it, values, onAction) }
        }
        is OwlNode.Box -> Box(modifier = commonModifier(node.props)) {
            node.children.forEach { RenderNode(it, values, onAction) }
        }
        is OwlNode.Text -> RenderText(node, values)
        is OwlNode.Spacer -> RenderSpacer(node)
        is OwlNode.Button -> RenderButton(node, values, onAction)
    }
}

@Composable
private fun ColumnScope.RenderInColumn(node: OwlNode, values: Map<String, String>, onAction: (WidgetAction) -> Unit) {
    val weight = node.props["weight"]?.toFloatOrNull()
    if (weight != null) {
        Box(modifier = Modifier.weight(weight)) { RenderNode(node, values, onAction) }
    } else {
        RenderNode(node, values, onAction)
    }
}

@Composable
private fun RowScope.RenderInRow(node: OwlNode, values: Map<String, String>, onAction: (WidgetAction) -> Unit) {
    val weight = node.props["weight"]?.toFloatOrNull()
    if (weight != null) {
        Box(modifier = Modifier.weight(weight)) { RenderNode(node, values, onAction) }
    } else {
        RenderNode(node, values, onAction)
    }
}

@Composable
private fun RenderText(node: OwlNode.Text, values: Map<String, String>) {
    val resolved = interpolate(node.props["text"] ?: "", values)
    val sizeSp = node.props["size"]?.toIntOrNull()?.sp ?: 14.sp
    val color = resolveColor(node.props["color"], MaterialTheme.colorScheme.onBackground)
    Text(
        text = resolved,
        fontSize = sizeSp,
        color = color,
        fontFamily = FontFamily.Monospace,
        modifier = commonModifier(node.props),
    )
}

@Composable
private fun RenderButton(node: OwlNode.Button, values: Map<String, String>, onAction: (WidgetAction) -> Unit) {
    val action = WidgetAction.parse(node.props["onTap"])
    val label = interpolate(node.props["text"] ?: "", values)
    OutlinedButton(onClick = { action?.let(onAction) }, modifier = commonModifier(node.props)) {
        Text(label, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RenderSpacer(node: OwlNode.Spacer) {
    val size = node.props["size"]?.toIntOrNull()?.dp ?: 8.dp
    Spacer(modifier = commonModifier(node.props).then(Modifier.size(size)))
}

@Composable
private fun commonModifier(props: Map<String, String>): Modifier {
    var modifier: Modifier = Modifier
    val radius = props["radius"]?.toIntOrNull() ?: 0
    props["background"]?.let { token ->
        modifier = modifier.background(resolveColor(token, Color.Transparent), RoundedCornerShape(radius.dp))
    }
    props["padding"]?.toIntOrNull()?.let { modifier = modifier.padding(it.dp) }
    when (props["fill"]) {
        "width" -> modifier = modifier.fillMaxWidth()
        "height" -> modifier = modifier.fillMaxHeight()
        "both" -> modifier = modifier.fillMaxSize()
    }
    return modifier
}

private fun spacingArrangementVertical(props: Map<String, String>): Arrangement.Vertical =
    props["spacing"]?.toIntOrNull()?.let { Arrangement.spacedBy(it.dp) } ?: Arrangement.Top

private fun spacingArrangementHorizontal(props: Map<String, String>): Arrangement.Horizontal =
    props["spacing"]?.toIntOrNull()?.let { Arrangement.spacedBy(it.dp) } ?: Arrangement.Start

@Composable
private fun resolveColor(token: String?, default: Color): Color = when (token) {
    null -> default
    "accent" -> MaterialTheme.colorScheme.primary
    "background" -> MaterialTheme.colorScheme.background
    "surface" -> MaterialTheme.colorScheme.surface
    "foreground" -> MaterialTheme.colorScheme.onBackground
    "subtle" -> MaterialTheme.colorScheme.onSurfaceVariant
    "red" -> OmarchyPalette.Red
    "green" -> OmarchyPalette.Green
    "yellow" -> OmarchyPalette.Yellow
    "magenta" -> OmarchyPalette.Magenta
    "cyan" -> OmarchyPalette.Cyan
    else -> default
}

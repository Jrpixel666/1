package com.omarchy.launcher.ui.nativewidgets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.omarchy.launcher.nativewidgets.NativeWidgetHostManager

/** Hosts one real Android widget (an `AppWidgetHostView`, a classic Android View) inside Compose. */
@Composable
fun NativeWidgetHostView(appWidgetId: Int, hostManager: NativeWidgetHostManager, modifier: Modifier = Modifier) {
    val info = remember(appWidgetId) { hostManager.getAppWidgetInfo(appWidgetId) } ?: return
    // minHeight/minWidth on AppWidgetProviderInfo are already in dp, not px.
    val heightDp = (if (info.minHeight > 0) info.minHeight else 80).dp
    AndroidView(
        modifier = modifier.fillMaxWidth().height(heightDp),
        factory = { hostManager.createHostView(appWidgetId, info) },
    )
}

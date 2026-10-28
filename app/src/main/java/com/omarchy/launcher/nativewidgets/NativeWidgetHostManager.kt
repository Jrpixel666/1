package com.omarchy.launcher.nativewidgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context

/**
 * Thin wrapper around Android's own widget-hosting framework (`AppWidgetHost`/
 * `AppWidgetManager`) - the real thing, not a custom language. Any installed app's widget
 * (clock, weather, calendar...) can be placed through this, unlike the OWL-inspired `.owl`
 * widgets in the `widgets` package, which are launcher-authored declarative files. The two
 * systems are independent and render in separate sections of the home screen.
 */
class NativeWidgetHostManager(private val context: Context) {

    companion object {
        /** Arbitrary but fixed - identifies this app's widget "host" to the system. */
        private const val HOST_ID = 1001
    }

    private val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)
    private val appWidgetHost: AppWidgetHost by lazy { AppWidgetHost(context, HOST_ID) }

    fun startListening() = appWidgetHost.startListening()
    fun stopListening() = appWidgetHost.stopListening()

    fun allocateAppWidgetId(): Int = appWidgetHost.allocateAppWidgetId()

    fun deleteAppWidgetId(appWidgetId: Int) = appWidgetHost.deleteAppWidgetId(appWidgetId)

    /** Attempts a dialog-free bind; most third-party apps aren't allow-listed, so this usually
     *  returns false and the caller must fall back to the ACTION_APPWIDGET_BIND system intent. */
    fun bindAppWidgetIdIfAllowed(appWidgetId: Int, provider: ComponentName): Boolean =
        appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)

    fun getAppWidgetInfo(appWidgetId: Int): AppWidgetProviderInfo? =
        appWidgetManager.getAppWidgetInfo(appWidgetId)

    /** Only providers meant for a home screen (as opposed to keyguard-only widgets). */
    fun installedHomeScreenProviders(): List<AppWidgetProviderInfo> =
        appWidgetManager.installedProviders.filter {
            (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0
        }

    fun createHostView(appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        appWidgetHost.createView(context, appWidgetId, info)
}

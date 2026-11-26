package com.xjb.medicalquestionai.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
internal fun FullScreenImageDialog(
    onDismiss: () -> Unit,
    containerColor: Color,
    content: @Composable () -> Unit
) {
    val edgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !edgeToEdge)
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val attrs = window.attributes
                    val cutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    // The dialog window can still fit system bars even when Compose measures edge to edge.
                    if (attrs.fitInsetsTypes != 0 || attrs.layoutInDisplayCutoutMode != cutoutMode) {
                        attrs.setFitInsetsTypes(0)
                        attrs.layoutInDisplayCutoutMode = cutoutMode
                        window.attributes = attrs
                    }
                }
                val darkIcons = containerColor.luminance() > 0.5f
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = darkIcons
                    isAppearanceLightNavigationBars = darkIcons
                }
            }
        }
        Box(Modifier.fillMaxSize().background(containerColor).safeDrawingPadding()) {
            content()
        }
    }
}

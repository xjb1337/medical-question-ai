package com.xjb.medicalquestionai.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/** Use one saved stack per tab, including shortcuts outside the bottom bar. */
fun NavController.openTopLevel(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Ignore another tap while the current destination is entering or leaving. */
fun NavController.openDetail(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    navigate(route) { launchSingleTop = true }
}

fun NavController.returnFromDetail() {
    if (!popBackStack()) openTopLevel("home")
}

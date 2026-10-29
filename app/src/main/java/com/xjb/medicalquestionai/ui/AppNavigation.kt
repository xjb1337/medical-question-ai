package com.xjb.medicalquestionai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.update.UpdateViewModel
import com.xjb.medicalquestionai.ui.components.UpdateAvailablePrompt
import com.xjb.medicalquestionai.ui.components.UpdateProgressDialog
import com.xjb.medicalquestionai.ui.screens.CameraScreen
import com.xjb.medicalquestionai.ui.screens.HistoryScreen
import com.xjb.medicalquestionai.ui.screens.NewQuestionScreen
import com.xjb.medicalquestionai.ui.screens.ResultScreen
import com.xjb.medicalquestionai.ui.screens.SettingsScreen

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val bottomDestinations = listOf(
    BottomDestination("home", "拍题", Icons.Outlined.Home),
    BottomDestination("history", "历史", Icons.Outlined.History),
    BottomDestination("settings", "设置", Icons.Outlined.Settings)
)

@Composable
fun MedicalQuestionApp(
    viewModel: MainViewModel = viewModel(),
    updateViewModel: UpdateViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == "home" || currentRoute == "history" || currentRoute == "settings"
    val updateState by updateViewModel.state.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val followUpSendingIds by viewModel.followUpSendingIds.collectAsState()
    val hasActiveWork = isAnalyzing || followUpSendingIds.isNotEmpty()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updateViewModel.onAppResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(currentRoute, hasActiveWork) {
        if (currentRoute == "home" && !hasActiveWork) updateViewModel.autoCheckIfDue()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                NewQuestionScreen(viewModel = viewModel, navController = navController)
            }
            composable(
                route = "camera?source={source}&sessionId={sessionId}",
                arguments = listOf(
                    navArgument("source") {
                        type = NavType.StringType
                        defaultValue = "home"
                    },
                    navArgument("sessionId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                CameraScreen(
                    viewModel = viewModel,
                    navController = navController,
                    source = entry.arguments?.getString("source").orEmpty(),
                    sessionId = entry.arguments?.getString("sessionId").orEmpty()
                )
            }
            composable("history") {
                HistoryScreen(viewModel = viewModel, navController = navController)
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    navController = navController,
                    updateViewModel = updateViewModel
                )
            }
            composable(
                route = "result/{questionId}",
                arguments = listOf(navArgument("questionId") { type = NavType.StringType })
            ) { entry ->
                ResultScreen(
                    viewModel = viewModel,
                    navController = navController,
                    questionId = entry.arguments?.getString("questionId").orEmpty()
                )
            }
        }

        if (currentRoute == "home" && !hasActiveWork) {
            UpdateAvailablePrompt(
                state = updateState,
                onDownload = updateViewModel::download,
                onDismiss = updateViewModel::dismissPrompt
            )
            UpdateProgressDialog(
                state = updateState,
                updateViewModel = updateViewModel,
                onDismiss = updateViewModel::dismissProgress,
                canInstall = !hasActiveWork
            )
        }
    }
}

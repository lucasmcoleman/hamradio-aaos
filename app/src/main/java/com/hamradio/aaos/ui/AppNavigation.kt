package com.hamradio.aaos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hamradio.aaos.ui.screens.AprsScreen
import com.hamradio.aaos.ui.screens.ChannelsScreen
import com.hamradio.aaos.ui.screens.DebugScreen
import com.hamradio.aaos.ui.screens.HomeScreen
import com.hamradio.aaos.ui.screens.SettingsScreen
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.Background
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.vm.MainViewModel

private sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home     : Screen("home",     "Home",     Icons.Default.Home)
    object Channels : Screen("channels", "Channels", Icons.AutoMirrored.Filled.List)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Aprs     : Screen("aprs",     "APRS",     Icons.Default.Map)
    object Debug    : Screen("debug",    "Debug",    Icons.Default.BugReport)
}

private val TOP_LEVEL = listOf(
    Screen.Home, Screen.Channels, Screen.Settings, Screen.Aprs,
)

@Composable
fun AppNavigation(vm: MainViewModel) {
    val navController = rememberNavController()
    val backEntry     by navController.currentBackStackEntryAsState()
    val currentRoute  = backEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error events as snackbars (H4)
    LaunchedEffect(Unit) {
        vm.errorEvent.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // AAOS-safe nav bar: tall touch targets, large icons
            NavigationBar(
                containerColor = SurfaceCard,
                tonalElevation = 0.dp,
            ) {
                TOP_LEVEL.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick  = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = {
                            Icon(
                                screen.icon,
                                contentDescription = screen.label,
                                modifier           = Modifier.size(28.dp),
                            )
                        },
                        label = {
                            Text(screen.label, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Accent,
                            selectedTextColor   = Accent,
                            indicatorColor      = Accent.copy(alpha = 0.15f),
                            unselectedIconColor = OnSurfaceMuted,
                            unselectedTextColor = OnSurfaceMuted,
                        ),
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController    = navController,
                startDestination = Screen.Home.route,
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(vm = vm, onNavigateToChannels = {
                        navController.navigate(Screen.Channels.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    })
                }
                composable(Screen.Channels.route) { ChannelsScreen(vm) }
                composable(Screen.Settings.route)  {
                    SettingsScreen(vm, onOpenDebug = {
                        navController.navigate(Screen.Debug.route)
                    })
                }
                composable(Screen.Aprs.route)      { AprsScreen(vm) }
                composable(Screen.Debug.route)     {
                    DebugScreen(vm, onClose = { navController.popBackStack() })
                }
            }
        }
    }
}

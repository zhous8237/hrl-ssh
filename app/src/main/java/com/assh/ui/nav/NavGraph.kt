package com.assh.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.assh.ui.home.HomeScreen
import com.assh.ui.hosts.HostEditScreen
import com.assh.ui.terminal.TerminalScreen

object Routes {
    const val HOME = "home"
    const val HOST_EDIT = "host_edit?hostId={hostId}"
    const val TERMINAL = "terminal/{hostId}"

    fun hostEdit(hostId: Long? = null) =
        if (hostId == null) "host_edit?hostId=-1" else "host_edit?hostId=$hostId"

    fun terminal(hostId: Long) = "terminal/$hostId"
}

@Composable
fun AsshNavGraph() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenTerminal = { nav.navigate(Routes.terminal(it)) },
                onEditHost = { nav.navigate(Routes.hostEdit(it)) },
                onAddHost = { nav.navigate(Routes.hostEdit()) }
            )
        }
        composable(
            Routes.HOST_EDIT,
            arguments = listOf(navArgument("hostId") { type = NavType.LongType; defaultValue = -1L })
        ) { entry ->
            val hostId = entry.arguments?.getLong("hostId")?.takeIf { it > 0 }
            HostEditScreen(hostId = hostId, onDone = { nav.popBackStack() })
        }
        composable(
            Routes.TERMINAL,
            arguments = listOf(navArgument("hostId") { type = NavType.LongType })
        ) { entry ->
            val hostId = entry.arguments!!.getLong("hostId")
            TerminalScreen(hostId = hostId, onBack = { nav.popBackStack() })
        }
    }
}

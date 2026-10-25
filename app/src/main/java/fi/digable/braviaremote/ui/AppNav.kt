package fi.digable.braviaremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.digable.braviaremote.data.TvStore
import fi.digable.braviaremote.protocol.DiscoveredTv
import fi.digable.braviaremote.ui.discover.DiscoverScreen
import fi.digable.braviaremote.ui.pair.FastKeysScreen
import fi.digable.braviaremote.ui.pair.PairScreen
import fi.digable.braviaremote.ui.remote.RemoteScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

object Routes {
    const val REMOTE = "remote"
    const val DISCOVER = "discover"
    const val FAST_KEYS = "fastkeys/{tvId}"
    const val PAIR = "pair/{host}?name={name}&model={model}&uuid={uuid}"

    fun fastKeys(tvId: String) = "fastkeys/${URLEncoder.encode(tvId, "UTF-8")}"

    fun pair(tv: DiscoveredTv): String {
        fun enc(s: String?) = URLEncoder.encode(s.orEmpty(), "UTF-8")
        return "pair/${enc(tv.host)}?name=${enc(tv.name)}&model=${enc(tv.model)}&uuid=${enc(tv.uuid)}"
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(store: TvStore) : ViewModel() {
    /** null until DataStore has answered, then whether any TV is saved. */
    val hasTv = store.savedTvs.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun AppNav(root: RootViewModel = hiltViewModel()) {
    val hasTv by root.hasTv.collectAsStateWithLifecycle()
    if (hasTv == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val nav = rememberNavController()
    // Decide the start destination once: changing it while the graph is live would rebuild the
    // back stack under the user's feet (pairing navigates to the remote explicitly instead).
    val startDestination = remember { if (hasTv == true) Routes.REMOTE else Routes.DISCOVER }
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.REMOTE) {
            RemoteScreen(
                onAddTv = { nav.navigate(Routes.DISCOVER) },
                onPairAgain = { tv ->
                    nav.navigate(Routes.pair(DiscoveredTv(tv.host, tv.name, tv.model, tv.uuid)))
                },
                onFastKeys = { tv -> nav.navigate(Routes.fastKeys(tv.id)) },
            )
        }
        composable(Routes.FAST_KEYS, arguments = listOf(navArgument("tvId") { type = NavType.StringType })) { entry ->
            FastKeysScreen(
                tvId = URLDecoder.decode(entry.arguments?.getString("tvId").orEmpty(), "UTF-8"),
                onClose = { nav.popBackStack() },
            )
        }
        composable(Routes.DISCOVER) {
            DiscoverScreen(
                canGoBack = hasTv == true,
                onBack = { nav.popBackStack() },
                onTvChosen = { tv -> nav.navigate(Routes.pair(tv)) },
            )
        }
        composable(
            Routes.PAIR,
            arguments = listOf(
                navArgument("host") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("model") { type = NavType.StringType; defaultValue = "" },
                navArgument("uuid") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            fun arg(key: String) = URLDecoder.decode(entry.arguments?.getString(key).orEmpty(), "UTF-8")
            val tv = DiscoveredTv(
                host = arg("host"),
                name = arg("name"),
                model = arg("model"),
                uuid = arg("uuid").ifBlank { null },
            )
            PairScreen(
                tv = tv,
                onCancel = { nav.popBackStack() },
                onPaired = {
                    nav.navigate(Routes.REMOTE) {
                        popUpTo(nav.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

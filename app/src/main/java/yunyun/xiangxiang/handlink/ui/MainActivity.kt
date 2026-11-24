package yunyun.xiangxiang.handlink.ui

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import yunyun.xiangxiang.handlink.AppContainer
import yunyun.xiangxiang.handlink.container
import yunyun.xiangxiang.handlink.data.model.AuthSession
import yunyun.xiangxiang.handlink.host.HostScreen
import yunyun.xiangxiang.handlink.host.HostViewModel
import yunyun.xiangxiang.handlink.host.ProjectionRequestDialog
import yunyun.xiangxiang.handlink.host.ProjectionStandbyService
import yunyun.xiangxiang.handlink.ui.home.HomeScreen
import yunyun.xiangxiang.handlink.ui.home.HomeViewModel
import yunyun.xiangxiang.handlink.ui.home.Routes
import yunyun.xiangxiang.handlink.ui.login.LoginScreen
import yunyun.xiangxiang.handlink.ui.login.LoginViewModel
import yunyun.xiangxiang.handlink.ui.manage.QuickTextsScreen
import yunyun.xiangxiang.handlink.ui.manage.BatchFilesScreen
import yunyun.xiangxiang.handlink.ui.manage.BatchFilesViewModel
import yunyun.xiangxiang.handlink.ui.manage.SharesScreen
import yunyun.xiangxiang.handlink.ui.manage.SharesViewModel
import yunyun.xiangxiang.handlink.ui.manage.TagsScreen
import yunyun.xiangxiang.handlink.ui.manage.UsersScreen
import yunyun.xiangxiang.handlink.ui.manage.UsersViewModel
import yunyun.xiangxiang.handlink.ui.settings.AboutScreen
import yunyun.xiangxiang.handlink.ui.settings.AppSettingsScreen
import yunyun.xiangxiang.handlink.ui.settings.CertificatesScreen
import yunyun.xiangxiang.handlink.ui.settings.StreamSettingsScreen
import yunyun.xiangxiang.handlink.ui.theme.HandLinkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val c = container
        setContent {
            val app by c.settings.app.collectAsStateWithLifecycle()
            val standby by ProjectionStandbyService.state.collectAsStateWithLifecycle()
            DisposableEffect(standby.unattended) {
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                val added = standby.unattended && (window.attributes.flags and flag) == 0
                if (added) window.addFlags(flag)
                onDispose { if (added) window.clearFlags(flag) }
            }
            HandLinkTheme(app.themeMode, app.themeColor) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Root(c)
                    ProjectionRequestDialog()
                }
            }
        }
    }
}

@Composable
private fun Root(c: AppContainer) {
    val session by c.sessions.session.collectAsStateWithLifecycle()
    var showHostScreen by remember { mutableStateOf(false) }
    val s = session
    if (showHostScreen) {
        val hvm: HostViewModel = viewModel(key = "host_root") { HostViewModel(c.appContext as Application) }
        HostScreen(
            vm = hvm,
            currentServerUrl = s?.serverUrl,
            onBack = { showHostScreen = false }
        )
    } else if (s == null) {
        val vm: LoginViewModel = viewModel(key = "login") { LoginViewModel(c) }
        LoginScreen(vm, onOpenHost = { showHostScreen = true })
    } else {
        LoggedIn(c, s)
    }
}

@Composable
private fun LoggedIn(c: AppContainer, s: AuthSession) {
    // key 绑定会话：切换账号时 ViewModel 与仓库全部重建
    val vm: HomeViewModel = viewModel(key = "home:${s.serverUrl}:${s.username}:${s.token.hashCode()}") { HomeViewModel(c, s) }
    val nav = rememberNavController()
    val me by vm.me.collectAsStateWithLifecycle()
    val api = c.sessions.api() ?: return

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable(Routes.HOME) { HomeScreen(vm) { route -> nav.navigate(route) } }
        composable(
            Routes.SHARES + "?deviceId={deviceId}&create={create}",
            arguments = listOf(
                navArgument("deviceId") { type = NavType.StringType; defaultValue = "" },
                navArgument("create") { type = NavType.BoolType; defaultValue = false },
            )
        ) { entry ->
            val deviceId = entry.arguments?.getString("deviceId").orEmpty().ifBlank { null }
            val create = entry.arguments?.getBoolean("create") ?: false
            val svm: SharesViewModel = viewModel { SharesViewModel(api, deviceId) }
            val devices by vm.devices.collectAsStateWithLifecycle()
            SharesScreen(svm, devices, create) { nav.popBackStack() }
        }
        composable(Routes.USERS) {
            val uvm: UsersViewModel = viewModel { UsersViewModel(api) }
            UsersScreen(uvm, s.username) { nav.popBackStack() }
        }
        composable(Routes.TAGS) { TagsScreen(vm.repo) { nav.popBackStack() } }
        composable(Routes.QUICK_TEXTS) { QuickTextsScreen(vm.quickTexts) { nav.popBackStack() } }
        composable(Routes.BATCH_FILES) {
            val scope = c.userScope()
            if (scope != null) {
                val bvm: BatchFilesViewModel = viewModel { BatchFilesViewModel(c.appContext, scope.serverFiles, scope.devices) }
                BatchFilesScreen(bvm) { nav.popBackStack() }
            }
        }
        composable(Routes.STREAM_SETTINGS) { StreamSettingsScreen(c.settings, me?.policy) { nav.popBackStack() } }
        composable(Routes.APP_SETTINGS) { AppSettingsScreen(c.settings) { nav.popBackStack() } }
        composable(Routes.CERTS) { CertificatesScreen(c.pinStore) { nav.popBackStack() } }
        composable(Routes.ABOUT) { AboutScreen { nav.popBackStack() } }
        composable(Routes.HOST) {
            val hvm: HostViewModel = viewModel(key = "host_logged_in") { HostViewModel(c.appContext as Application) }
            HostScreen(vm = hvm, currentServerUrl = s.serverUrl) { nav.popBackStack() }
        }
    }
}

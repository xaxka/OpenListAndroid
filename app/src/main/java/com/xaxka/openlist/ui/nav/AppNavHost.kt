package com.xaxka.openlist.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xaxka.openlist.R
import com.xaxka.openlist.ui.main.HomeScreen
import com.xaxka.openlist.ui.main.HomeViewModel
import com.xaxka.openlist.ui.settings.EasyTierSettingsScreen
import com.xaxka.openlist.ui.settings.EasyTierStatusDetailScreen
import com.xaxka.openlist.ui.settings.SettingsScreen
import com.xaxka.openlist.ui.settings.VideoHashSettingsScreen
import com.xaxka.openlist.ui.theme.AnimPageFade
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.web.WebScreen
import com.xaxka.openlist.ui.web.WebViewModel
import com.xaxka.openlist.ui.web.WebViewStateHolder

/** 底部导航路由（顺序/文案照源 main.dart：网页 / OpenList / 设置，默认选中主页） */
object Routes {
    const val WEB = "web"
    const val HOME = "home"
    const val SETTINGS = "settings"

    /** 设置子页面（设置页内入口进入，返回回到设置页） */
    const val SETTINGS_VIDEOHASH = "settings/videohash"
    const val SETTINGS_EASYTIER = "settings/easytier"

    /** 内网映射「映射状态」详情页：本节点 + 事件日志（点「映射状态」进入，返回回到内网映射页） */
    const val SETTINGS_EASYTIER_STATUS = "settings/easytier/status"
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector? = null,
    val logoRes: Int? = null,
)

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    // Activity 级共享 VM：openWebEvent（自动跳网页）与再点网页 tab 重载（源 onClickNavigationBar）
    val activity = LocalContext.current as ComponentActivity
    val homeViewModel: HomeViewModel = viewModel(activity)
    val webViewModel: WebViewModel = viewModel(activity)
    // 跨 tab 切换保留 WebView 实例；Activity 销毁时统一清理
    val webStateHolder = remember { WebViewStateHolder() }
    DisposableEffect(webStateHolder) {
        onDispose { webStateHolder.destroy() }
    }

    val tabs = remember {
        listOf(
            TabItem(Routes.WEB, "网页", icon = Icons.Filled.Preview),
            TabItem(Routes.HOME, "OpenList", logoRes = R.drawable.openlist_logo),
            TabItem(Routes.SETTINGS, "设置", icon = Icons.Filled.Settings),
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 服务已运行且勾选「自动打开网页」→ 启动直达网页页（源 main.dart 行为）
    LaunchedEffect(Unit) {
        homeViewModel.openWebEvent.collect {
            if (navController.currentDestination?.route == Routes.HOME) {
                navController.navigate(Routes.WEB) {
                    launchSingleTop = true
                    popUpTo(Routes.HOME)
                }
            }
        }
    }

    Scaffold(
        // 各页自管顶部 inset（主页 TopAppBar / 网页页状态栏占位 / 设置页 statusBarsPadding），
        // 关闭默认 systemBars 注入避免与页面内 padding 叠加成双倍留白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { tab ->
                    // 设置子页面（settings/*）仍归属「设置」tab 高亮
                    val selected = currentRoute == tab.route ||
                        (tab.route == Routes.SETTINGS && currentRoute?.startsWith(Routes.SETTINGS) == true)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute == tab.route) {
                                // 已在网页页时再点网页 tab → 重载（源 onClickNavigationBar）
                                if (tab.route == Routes.WEB) webViewModel.requestReload()
                            } else {
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            when {
                                tab.icon != null ->
                                    Icon(tab.icon, contentDescription = tab.label)
                                tab.logoRes != null -> Icon(
                                    painter = painterResource(tab.logoRes),
                                    contentDescription = tab.label,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // 与两侧 Material 图标同为 24dp：NavigationBarItem 的
                                    // 图标+文字按列垂直居中，中间图标偏大（曾用 32dp）
                                    // 会把 "OpenList" 文字下压约 4dp，三个标签文字错位
                                    modifier = Modifier.size(Dimens.NavIconSize),
                                )
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(AnimPageFade) },
            exitTransition = { fadeOut(AnimPageFade) },
            popEnterTransition = { fadeIn(AnimPageFade) },
            popExitTransition = { fadeOut(AnimPageFade) },
        ) {
            composable(Routes.HOME) { HomeScreen(viewModel = homeViewModel) }
            composable(Routes.WEB) { WebScreen(viewModel = webViewModel, stateHolder = webStateHolder) }
            composable(Routes.SETTINGS) { SettingsScreen(navController = navController) }
            composable(Routes.SETTINGS_VIDEOHASH) { VideoHashSettingsScreen(navController = navController) }
            composable(Routes.SETTINGS_EASYTIER) { EasyTierSettingsScreen(navController = navController) }
            composable(Routes.SETTINGS_EASYTIER_STATUS) { EasyTierStatusDetailScreen(navController = navController) }
        }
    }
}

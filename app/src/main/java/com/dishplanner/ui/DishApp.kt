package com.dishplanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dishplanner.DishPlannerApp
import com.dishplanner.ui.screens.CreateRecipeScreen
import com.dishplanner.ui.screens.DraftReviewScreen
import com.dishplanner.ui.screens.JoinGroupScreen
import com.dishplanner.ui.screens.OnboardingScreen
import com.dishplanner.ui.screens.RecipeDetailScreen
import com.dishplanner.ui.screens.RecipeListScreen
import com.dishplanner.ui.screens.SettingsScreen
import com.dishplanner.ui.screens.SplashScreen
import com.dishplanner.ui.theme.DishPlannerTheme
import com.dishplanner.ui.theme.Palette
import kotlinx.coroutines.delay

/** 路由名集中在这里，避免字符串散落 */
object Routes {
    const val LIST = "list"
    const val DETAIL = "detail/{id}"
    const val CREATE = "create"
    const val DRAFT = "draft"
    const val SETTINGS = "settings"
    const val JOIN = "join"

    fun detail(id: String) = "detail/$id"
}

@Composable
fun DishApp() {
    val app = LocalContext.current.applicationContext as DishPlannerApp
    val vm: DishViewModel = viewModel(factory = DishViewModel.Factory(app.graph))
    val nav = rememberNavController()

    val settings by vm.settings.collectAsState()
    val identity by vm.identity.collectAsState()
    val booted by vm.booted.collectAsState()

    LaunchedEffect(Unit) { vm.boot() }

    // 启动图最短显示时长。
    // boot() 通常几十毫秒就回来了，直接把 Splash 撤掉会闪一下白，比不画还难看；
    // 所以给一个下限，让「瓷盘浮上来 + 字样淡入」这条动效至少走完一遍。
    var splashElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(720)
        splashElapsed = true
    }

    val palette = Palette.byId(settings.paletteId)
    val dark = when (settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    DishPlannerTheme(
        palette = palette,
        darkTheme = dark,
        dynamicColor = settings.dynamicColor,
    ) {
        if (!booted) {
            // 首帧：读身份的几十毫秒，用同一张启动图顶住，避免白闪
            SplashScreen()
            return@DishPlannerTheme
        }

        // 读完了也再留一会儿，让启动动效走完；此后若还在加载则继续显示
        if (!splashElapsed) {
            SplashScreen()
            return@DishPlannerTheme
        }

        if (identity == null) {
            OnboardingScreen(
                vm = vm,
                onDone = { /* identity flow 自动驱动下面的分支 */ },
            )
            return@DishPlannerTheme
        }

        NavHost(navController = nav, startDestination = Routes.LIST) {
            composable(Routes.LIST) {
                RecipeListScreen(
                    vm = vm,
                    onOpenRecipe = { id -> nav.navigate(Routes.detail(id)) },
                    onCreate = { nav.navigate(Routes.CREATE) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.DETAIL) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                RecipeDetailScreen(
                    vm = vm,
                    recipeId = id,
                    onBack = { nav.popBackStack() },
                    onDeleted = { nav.popBackStack() },
                )
            }

            composable(Routes.CREATE) {
                val draft by vm.draft.collectAsState()
                val ctx = LocalContext.current

                // 草稿出现后跳到预览页
                LaunchedEffect(draft?.id) {
                    if (draft != null) nav.navigate(Routes.DRAFT) {
                        popUpTo(Routes.CREATE) { inclusive = true }
                    }
                }
                CreateRecipeScreen(
                    vm = vm,
                    onBack = {
                        vm.discardDraft()
                        nav.popBackStack()
                    },
                    onGenerated = { /* 由上面的 LaunchedEffect 接管 */ },
                )
            }

            composable(Routes.DRAFT) {
                val draft by vm.draft.collectAsState()
                val current = draft
                if (current == null) {
                    // 草稿被放弃（比如进程回调丢失），退回列表
                    LaunchedEffect(Unit) { nav.popBackStack(Routes.LIST, false) }
                } else {
                    DraftReviewScreen(
                        vm = vm,
                        recipe = current,
                        onBack = {
                            vm.discardDraft()
                            nav.popBackStack(Routes.LIST, false)
                        },
                        onSaved = { id ->
                            nav.popBackStack(Routes.LIST, false)
                        },
                    )
                }
            }

            composable(Routes.SETTINGS) {
                val group by vm.group.collectAsState()
                SettingsScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                )
                // 主机/加入子流程的入口在设置页里通过 vm 状态驱动，这里用一次性的
                // 观察者把「用户想加入」的意图转成导航
                LaunchedEffect(Unit) {
                    if (vm.joinFlow) {
                        vm.joinFlow = false
                        nav.navigate(Routes.JOIN)
                    }
                }
            }

            composable(Routes.JOIN) {
                JoinGroupScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

package io.github.Calvariaa.kitchennotes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.Calvariaa.kitchennotes.KitchenNotesApp
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.data.FamilyGroup
import io.github.Calvariaa.kitchennotes.ui.components.AppDrawer
import io.github.Calvariaa.kitchennotes.ui.components.DrawerDestination
import io.github.Calvariaa.kitchennotes.ui.i18n.ProvideAppLocale
import io.github.Calvariaa.kitchennotes.ui.screens.CreateRecipeScreen
import io.github.Calvariaa.kitchennotes.ui.screens.DraftReviewScreen
import io.github.Calvariaa.kitchennotes.ui.screens.JoinGroupScreen
import io.github.Calvariaa.kitchennotes.ui.screens.MenuEditorScreen
import io.github.Calvariaa.kitchennotes.ui.screens.MenuListScreen
import io.github.Calvariaa.kitchennotes.ui.screens.OnboardingScreen
import io.github.Calvariaa.kitchennotes.ui.screens.RecipeDetailScreen
import io.github.Calvariaa.kitchennotes.ui.screens.RecipeListScreen
import io.github.Calvariaa.kitchennotes.ui.screens.SettingsScreen
import io.github.Calvariaa.kitchennotes.ui.screens.SplashScreen
import io.github.Calvariaa.kitchennotes.ui.theme.KitchenNotesTheme
import io.github.Calvariaa.kitchennotes.ui.theme.Palette
import kotlinx.coroutines.delay

/** 路由名集中在这里，避免字符串散落 */
object Routes {
    const val LIST = "list"
    const val MENUS = "menus"
    const val DETAIL = "detail/{id}"
    const val MENU_EDITOR = "menu/{id}"
    const val CREATE = "create"
    const val DRAFT = "draft"
    const val SETTINGS = "settings"
    const val JOIN = "join"

    fun detail(id: String) = "detail/$id"
    fun menuEditor(id: String) = "menu/$id"
}

@Composable
fun KitchenNotesRoot() {
    val app = LocalContext.current.applicationContext as KitchenNotesApp
    val vm: DishViewModel = viewModel(factory = DishViewModel.Factory(app.graph))
    val nav = rememberNavController()

    val settings by vm.settings.collectAsState()
    val identity by vm.identity.collectAsState()
    val booted by vm.booted.collectAsState()
    val group by vm.group.collectAsState()

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
        else -> isSystemInDarkTheme()
    }

    KitchenNotesTheme(
        palette = palette,
        darkTheme = dark,
        dynamicColor = settings.dynamicColor,
    ) {
        // 语言覆盖必须包在主题内、导航外：它替换 LocalContext，
        // 下面所有 stringResource 与 UiText.text() 都会读到目标语言。
        // 放在这里而不是 MainActivity，是因为语言是设置项，要随设置实时变。
        ProvideAppLocale(settings.language) {
            if (!booted) {
                // 首帧：读身份的几十毫秒，用同一张启动图顶住，避免白闪
                SplashScreen()
                return@ProvideAppLocale
            }

            // 读完了也再留一会儿，让启动动效走完；此后若还在加载则继续显示
            if (!splashElapsed) {
                SplashScreen()
                return@ProvideAppLocale
            }

            if (identity == null) {
                OnboardingScreen(
                    vm = vm,
                    onDone = { /* identity flow 自动驱动下面的分支 */ },
                )
                return@ProvideAppLocale
            }

            // 设置页里点「加入家庭组」会置 joinFlow，这里把它转成一次导航。
            // 放在 NavHost 之外，避免导航目标自己触发自己的重组。
            LaunchedEffect(vm.joinFlow) {
                if (vm.joinFlow) {
                    vm.joinFlow = false
                    nav.navigate(Routes.JOIN)
                }
            }

            // 当前处于哪个一级页面 —— 决定抽屉高亮哪一项、点抽屉往哪走。
            // 用 NavHost 的返回栈状态而不是另建一个变量：否则「返回键退出设置页」
            // 和「点抽屉切页」会得到两个互相矛盾的真值来源。
            val backStack by nav.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            val drawerCurrent = when (currentRoute) {
                Routes.MENUS -> DrawerDestination.MENUS
                Routes.SETTINGS -> DrawerDestination.SETTINGS
                // 二级页面（详情、编辑器、新建）都挂在某个一级页面下，
                // 这里不参与抽屉高亮，抽屉只在一级页面才可见
                else -> DrawerDestination.RECIPES
            }

            // 只有一级页面才套抽屉：详情页自带返回箭头，再包一层抽屉
            // 会让「从右滑」这类手势和页面的横向滚动抢事件。
            //
            // 关键：NavHost 必须**只出现在一处**。曾经写成
            // `if (firstLevel) AppDrawer { AppNavHost() } else AppNavHost()`，
            // 结果进出二级页面时整棵 NavHost 被销毁重建，返回栈直接丢失。
            // 现在固定「NavHost 一直是 child，抽屉是可选中途关闭的外壳」，
            // 二级页面只是关掉手势，内容和状态都不重建。
            val firstLevel = currentRoute == Routes.LIST || currentRoute == Routes.MENUS

            val headerSubtitle = group?.let {
                when (it.role) {
                    FamilyGroup.Role.HOST -> stringResource(R.string.list_role_host, it.name)
                    FamilyGroup.Role.MEMBER -> stringResource(R.string.list_role_member, it.name)
                    FamilyGroup.Role.NONE -> null
                }
            }

            AppDrawer(
                current = drawerCurrent,
                onNavigate = { dest ->
                    val target = when (dest) {
                        DrawerDestination.RECIPES -> Routes.LIST
                        DrawerDestination.MENUS -> Routes.MENUS
                        DrawerDestination.SETTINGS -> Routes.SETTINGS
                    }
                    if (target != currentRoute) {
                        nav.navigate(target) {
                            // 一级页面之间是并列关系，不该堆栈。
                            // 但 LIST 必须留在栈底：设置页虽然不挂抽屉，它的返回箭头
                            // 仍然要能退回来，把 LIST 一起弹掉会让那个箭头点了没反应。
                            popUpTo(Routes.LIST) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                headerTitle = stringResource(R.string.app_name),
                headerSubtitle = headerSubtitle,
                gesturesEnabled = firstLevel,
            ) {
                AppNavHost(vm = vm, nav = nav)
            }
        }
    }
}

/** NavHost 单独抽出来，让「有抽屉」和「没抽屉」两种外壳共用同一份路由表 */
@Composable
private fun AppNavHost(
    vm: DishViewModel,
    nav: androidx.navigation.NavHostController,
) {
    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            RecipeListScreen(
                vm = vm,
                onOpenRecipe = { id -> nav.navigate(Routes.detail(id)) },
                onCreate = { nav.navigate(Routes.CREATE) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.MENUS) {
            MenuListScreen(
                vm = vm,
                onOpenMenu = { id -> nav.navigate(Routes.menuEditor(id)) },
            )
        }

        composable(Routes.MENU_EDITOR) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            MenuEditorScreen(
                vm = vm,
                menuId = id,
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.DETAIL) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            RecipeDetailScreen(
                vm = vm,
                recipeId = id,
                onBack = { nav.popBackStack() },
                onDeleted = { nav.popBackStack() },
                onOpenMenu = { menuId -> nav.navigate(Routes.menuEditor(menuId)) },
            )
        }

        composable(Routes.CREATE) {
            val draft by vm.draft.collectAsState()

            // 草稿出现后跳到预览页；popUpTo 掉新建页，
            // 这样在预览页按返回是回列表，而不是回到提交过的表单
            LaunchedEffect(draft?.id) {
                if (draft != null) {
                    nav.navigate(Routes.DRAFT) {
                        popUpTo(Routes.CREATE) { inclusive = true }
                    }
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
                    onSaved = { _ ->
                        nav.popBackStack(Routes.LIST, false)
                    },
                )
            }
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.JOIN) {
            JoinGroupScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

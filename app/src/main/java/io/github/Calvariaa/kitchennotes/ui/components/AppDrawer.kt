package io.github.Calvariaa.kitchennotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu as MenuIcon
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.Calvariaa.kitchennotes.R
import kotlinx.coroutines.launch

/** 抽屉里的三个去处 */
enum class DrawerDestination(val labelRes: Int) {
    RECIPES(R.string.nav_recipes),
    MENUS(R.string.nav_menus),
    SETTINGS(R.string.nav_settings),
}

/**
 * 抽屉占屏宽的比例。
 *
 * 纯比例会撞上可用性下限：411dp 的手机三分之一只有 137dp，剥掉图标和内边距后
 * 留给文字的不到 40dp，标题会被压成竖排。所以下面再兜一个地板，只有平板/横屏
 * 才真正吃到 1/3。
 */
private const val DRAWER_WIDTH_FRACTION = 1f / 3f
private val DrawerMinWidth = 180.dp
private val DrawerMaxWidth = 320.dp

/** 窄栏阈值：低于它就收起头像和副标题，否则表头会被挤爆 */
private val DrawerCompactWidth = 210.dp

/**
 * 抽屉开合动画期间的点击拦截层。
 *
 * 为什么需要它：`ModalNavigationDrawer` 的遮罩是
 * `Scrim(open = drawerState.isOpen, ...)`，而 `isOpen` 读的是
 * `anchoredDraggableState.settledValue` —— **动画进行中它还是 Closed**。
 * 于是整个打开动画期间遮罩不带 pointerInput，点在页面那一侧会直接穿到
 * 下面那一页上，可能顺手把某道菜打开（被滑入的抽屉挡着，当时看不见）。
 *
 * 它只要「存在」就够：Compose 的命中测试对同层兄弟只选一条路径，
 * 一个盖在 content 之上、带 pointerInput 的满屏 Box 会让下层收不到事件。
 * 再加一记 Final pass 的抬起消费，是防下层走 `PointerEventPass.Initial` 的旁路。
 *
 * 放在 content 内部而不是套在 ModalNavigationDrawer 外面很关键：
 * 拦截层是 `anchoredDraggable` 的后代，祖先仍在命中路径上，
 * 所以「动画中按下去重新拖动抽屉」照常可用；套到最外层就会把拖动一起吃掉。
 */
@Composable
private fun DrawerAnimationBlocker(active: Boolean) {
    if (!active) return
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final)
                            .changes
                            .forEach { if (it.changedToUp()) it.consume() }
                    }
                }
            },
    )
}

/**
 * 「打开抽屉」的动作。
 *
 * 用 CompositionLocal 而不是把 drawerState 一路当参数传：每个一级页面都要在顶栏
 * 放一个汉堡按钮，而抽屉在它们外面，位置固定。传参会让每个屏幕的签名都多两个
 * 和自己业务无关的参数。默认值是空实现，这样在抽屉之外预览单个屏幕也不会崩。
 */
val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }

/** 顶栏上的汉堡按钮，一级页面通用 */
@Composable
fun DrawerMenuButton() {
    val open = LocalOpenDrawer.current
    IconButton(onClick = open) {
        Icon(
            Icons.Filled.MenuIcon,
            contentDescription = stringResource(R.string.nav_open_drawer),
        )
    }
}

/**
 * 全局左侧滑栏。
 *
 * 用 [ModalNavigationDrawer] 而不是自己拼：它自带边缘滑动手势、遮罩层点击关闭、
 * 无障碍的「关闭抽屉」语义，以及 Android 上用户期待的返回键行为。
 *
 * 抽屉只负责「切到哪个一级页面」。菜谱详情、新建菜谱这些是二级页面，
 * 走原有的 NavHost 前进后退，不放进抽屉里 —— 抽屉项应该是并列的目的地，
 * 塞进「某道菜」会让返回栈变得无法预测。
 */
@Composable
fun AppDrawer(
    current: DrawerDestination,
    onNavigate: (DrawerDestination) -> Unit,
    headerTitle: String,
    headerSubtitle: String?,
    /**
     * 是否允许边缘滑动拉出。
     *
     * 二级页面（菜谱详情、菜单编辑）里必须关掉：那些页面有横向滚动的内容，
     * 边缘手势会和它们抢事件；而且它们的返回箭头已经承担了「回去」的语义。
     */
    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer = remember(scope, drawerState) {
        { scope.launch { drawerState.open() }; Unit }
    }

    CompositionLocalProvider(LocalOpenDrawer provides openDrawer) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = {
                /*
                 * 宽度按屏幕比例算，不写死 dp。
                 *
                 * ModalDrawerSheet 默认只受 DrawerDefaults.MaximumDrawerWidth(360dp) 约束，
                 * 在 411dp 的手机上就等于铺满整屏 —— 观感变成「换了个页面」而不是「拉出了侧栏」。
                 * 这里取屏幕宽度的三分之一，小屏自然更窄，平板/横屏也不会变成一条巨型边栏。
                 */
                BoxWithConstraints {
                    val drawerWidth = (maxWidth * DRAWER_WIDTH_FRACTION)
                        .coerceIn(DrawerMinWidth, DrawerMaxWidth)
                    ModalDrawerSheet(
                        modifier = Modifier.width(drawerWidth),
                        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                    ) {
                        DrawerHeader(
                            title = headerTitle,
                            subtitle = headerSubtitle,
                            compact = drawerWidth < DrawerCompactWidth,
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Spacer(Modifier.padding(top = 4.dp))

                        DrawerDestination.entries.forEach { dest ->
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        fontWeight = if (dest == current) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                },
                                icon = { Icon(dest.icon(), contentDescription = null) },
                                selected = dest == current,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    if (dest != current) onNavigate(dest)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                // 窄栏里 12dp 的外边距会把文字挤到换行，压到 8dp
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            content = {
                Box(Modifier.fillMaxSize()) {
                    content()
                    // 开合动画期间挡住页面：遮罩此时还没接管点击，
                    // 不挡的话手快的点按会穿到下面那一页上
                    DrawerAnimationBlocker(active = drawerState.isAnimationRunning)
                }
            },
        )
    }
}

private fun DrawerDestination.icon() = when (this) {
    DrawerDestination.RECIPES -> Icons.Filled.Restaurant
    DrawerDestination.MENUS -> Icons.Outlined.CalendarMonth
    DrawerDestination.SETTINGS -> Icons.Filled.Settings
}

/**
 * 抽屉表头。
 *
 * [compact] 为真是窄栏（手机竖屏三分之一）的形态：头像从 44dp 收到 32dp、
 * 左右外边距减半、副标题让位。否则「家庭菜谱 · 已连接 3 台设备」这类副标题
 * 会把标题挤成两行，看起来像排版事故。
 */
@Composable
private fun DrawerHeader(title: String, subtitle: String?, compact: Boolean) {
    val avatarSize = if (compact) 32.dp else 44.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(
                start = if (compact) 14.dp else 20.dp,
                end = if (compact) 10.dp else 20.dp,
                top = if (compact) 24.dp else 32.dp,
                bottom = if (compact) 12.dp else 18.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
            )
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact && !subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

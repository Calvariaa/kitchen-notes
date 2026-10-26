package io.github.Calvariaa.kitchennotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                ) {
                    DrawerHeader(headerTitle, headerSubtitle)

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.padding(top = 4.dp))

                    DrawerDestination.entries.forEach { dest ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = stringResource(dest.labelRes),
                                    fontWeight = if (dest == current) FontWeight.SemiBold
                                    else FontWeight.Normal,
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
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
            },
            content = content,
        )
    }
}

private fun DrawerDestination.icon() = when (this) {
    DrawerDestination.RECIPES -> Icons.Filled.Restaurant
    DrawerDestination.MENUS -> Icons.Outlined.CalendarMonth
    DrawerDestination.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun DrawerHeader(title: String, subtitle: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

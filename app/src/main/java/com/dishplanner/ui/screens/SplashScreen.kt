package com.dishplanner.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dishplanner.R

/**
 * 品牌底色。
 *
 * 为什么不直接用 `colorScheme.primary`：
 * 启动图的出现时机在设置读出来之前，而且要和系统开屏（values-v31 里那个固定的
 * `ic_launcher_background`）严丝合缝地接上。品牌色一旦跟着用户配色变，
 * 系统开屏和 Compose 这一帧就会撞色跳一下。所以这里写死。
 */
private val BrandClay = Color(0xFF8E3F13)

/**
 * 启动图。
 *
 * 两个来源必须看起来是同一个东西：
 *  1. Android 12+ 的系统开屏（`windowSplashScreenAnimatedIcon`）—— 只有图标，没字。
 *  2. 这一屏 —— 同一个圆角块 + 同一个瓷盘，再补上 DishPlanner 字样。
 *
 * 所以圆角块尺寸与 `ic_launcher_foreground` 的安全区对齐（图标有效直径 ≈ 62/108），
 * 视觉上就是系统那个图标放大了一点，然后文字从下方浮上来。
 *
 * 动效只用一条弹簧 + 两条淡入：启动图用花哨动画，用户第二次就烦了。
 */
@Composable
fun SplashScreen() {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val plateScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.82f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "plateScale",
    )
    val plateAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 380, easing = LinearEasing),
        label = "plateAlpha",
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 460, delayMillis = 180),
        label = "textAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 品牌块：和启动器图标同一个形状语言 —— 大圆角、扁平底色、瓷盘居中
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .scale(plateScale)
                    .alpha(plateAlpha)
                    .background(
                        color = BrandClay,
                        shape = RoundedCornerShape(34.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo_plate),
                    contentDescription = null,
                    modifier = Modifier.size(76.dp),
                )
            }

            Spacer(Modifier.height(26.dp))

            Text(
                text = "DishPlanner",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(textAlpha),
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "家庭菜谱",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(textAlpha),
            )
        }
    }
}

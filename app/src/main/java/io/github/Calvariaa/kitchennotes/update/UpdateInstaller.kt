package io.github.Calvariaa.kitchennotes.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用内安装。
 *
 * 走 `ACTION_VIEW` + `application/vnd.android.package-archive`，
 * 而不是 `PackageInstaller` 的 Session API：
 *  - Session API 需要自己处理 `PendingIntent` 的回执、进度和用户取消，
 *    而且不同厂商 ROM 上回执经常不来，失败态很难区分。
 *  - ACTION_VIEW 直接调起系统安装界面，用户在系统 UI 里看见包名和权限，
 *    安全感和可预期性都比自定义流程好，代码量为零。
 *
 * 两件必须处理的事：
 *  1. **FileProvider**：APK 放在私有目录，Android 7+ 不能交 `file://` 给别的进程，
 *     必须用 content:// 并授予临时读权限，且一次只授给一个包。
 *  2. **REQUEST_INSTALL_PACKAGES**：Android 8+ 需要用户在系统设置里允许
 *     「安装未知应用」。未授权时不能直接弹安装，否则用户看到的是
 *     「解析包时出现问题」这种毫无信息量的错误 —— 必须先引导去授权页。
 */
object UpdateInstaller {

    /** 未知来源安装权限是否已授予（Android 8 以下恒为 true） */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳系统设置里的「安装未知应用」授权页，包名带上自己，用户直接就在这里开关 */
    fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }

    /**
     * 拉起系统安装界面。
     *
     * @return 拉起成功返回 true；没有能处理该 Intent 的 Activity（极少数被裁过的 ROM）
     *         或权限未授予时返回 false，调用方据此提示用户。
     */
    fun install(ctx: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() == 0L) return false
        if (!canInstall(ctx)) {
            requestInstallPermission(ctx)
            return false
        }

        val uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            apk,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Android 7 起必须显式授权，否则系统进程读不到这个 content://
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

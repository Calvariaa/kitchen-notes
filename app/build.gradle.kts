plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ── 版本号：tag 驱动（语义化）+ 日期式 versionCode ────────────────────────────
// versionName（tag 形如 v1.4.0 / v2.0.0-rc.1）:
//   HEAD 正好在 tag 上   → 1.4.0
//   tag 之后有 N 个提交  → 1.4.1-dev.N+g<sha7>   （自动抬 patch，表示"下一个版本"）
//   仓库还没有任何 tag   → 0.0.0-dev.N+g<sha7>
// versionCode = HEAD 提交日期 yyyyMMdd（用提交自带的时区，跨机器可复现；
//   同一天发两次需要显式覆盖）。当前日期在 2100000000 以内，不会撞 AGP 上限。
// 覆盖（CI / 本地都可用）:
//   -Pkitchennotes.versionName=1.4.0 -Pkitchennotes.versionCode=20261022
fun gitOut(vararg args: String): String? = try {
    val exec = providers.exec {
        commandLine(listOf("git", "-C", rootDir.absolutePath) + args.toList())
        isIgnoreExitValue = true
    }
    val text = exec.standardOutput.asText.get().trim()
    if (exec.result.get().exitValue == 0 && text.isNotEmpty()) text else null
} catch (_: Throwable) {
    null // 不是 git 工作区（源码包 / 导出目录）时静默降级
}

// versionCode 必须严格递增，否则同一天发两次release时 Android 直接拒绝覆盖安装
// （INSTALL_FAILED_VERSION_DOWNGRADE），应用内更新也会判定成「不是新版本」。
// 日期只到天，所以乘 100 给当天留两位序号：2026102600、2026102601……
// 上限 2147483647 / 100 ≈ 2147 年，够用。
val autoVersionCode: Int = run {
    val stamp = gitOut("log", "-1", "--format=%cd", "--date=short") ?: ""
    val day = stamp.replace("-", "").toIntOrNull()?.takeIf { it > 0 }
    if (day == null) {
        1
    } else {
        // 当天序号 = 同一天里比本提交更早（或同为）HEAD 的 v* tag 数量。
        // 不用 rev-list --count：那个数会因 merge 而跳，且和「第几次发版」无关。
        val exactTag = gitOut("describe", "--tags", "--exact-match", "HEAD")
        // --merged HEAD：只数「已经在本提交历史上」的 tag。
        // 这样重跑旧 tag 也能得到同一个号 —— 若数的是仓库里全部同日 tag，
        // 后来新增的 tag 会把旧版本的号推进，构建就不再可复现了。
        val tagsSameDay = gitOut("tag", "--list", "--merged", "HEAD", "v[0-9]*")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.count { tag ->
                gitOut("log", "-1", "--format=%cd", "--date=short", tag) == stamp
            } ?: 0
        // 正好打在 tag 上时，这个 tag 自己也被算进去了，要减掉
        val seq = if (exactTag != null) tagsSameDay - 1 else tagsSameDay
        day * 100 + seq.coerceIn(0, 99)
    }
}

val autoVersionName: String = run {
    val describe = gitOut("describe", "--tags", "--long", "--match", "v[0-9]*")
    val m = describe?.let {
        Regex("""^(?<tag>.+)-(?<ahead>\d+)-g(?<sha>[0-9a-f]{7,})$""").find(it)
    }
    if (m == null) {
        val n = gitOut("rev-list", "--count", "HEAD") ?: "0"
        val sha = gitOut("rev-parse", "--short=7", "HEAD") ?: "nogit"
        "0.0.0-dev.$n+g$sha"
    } else {
        val tag = m.groups["tag"]!!.value.removePrefix("v")
        val ahead = m.groups["ahead"]!!.value.toIntOrNull() ?: 0
        val sha = m.groups["sha"]!!.value
        if (ahead == 0) {
            tag
        } else {
            val next = Regex("""^(\d+)\.(\d+)\.(\d+)$""").find(tag)?.let { base ->
                val (major, minor, patch) = base.destructured
                "$major.$minor.${patch.toInt() + 1}"
            } ?: tag
            "$next-dev.$ahead+g$sha"
        }
    }
}

val appVersionName: String =
    providers.gradleProperty("kitchennotes.versionName").getOrElse(autoVersionName)
val appVersionCode: Int =
    providers.gradleProperty("kitchennotes.versionCode").getOrElse(autoVersionCode.toString())
        .toIntOrNull() ?: autoVersionCode

android {
    namespace = "io.github.Calvariaa.kitchennotes"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.Calvariaa.kitchennotes"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        // 资源里只有 en / zh-rCN 两套，明确声明，让 AGP 不要为缺省语言发警告
        resourceConfigurations += listOf("en", "zh-rCN")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 故意不配 signingConfig：AGP 只产出 *-unsigned.apk，
            // 由 CI（.github/workflows/android.yml）用固定的 debug keystore 重签。
            // 这样签名密钥不在仓库里，也保证多次 CI 产物之间可以覆盖升级。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // JGit 7.x 是给 Java 11 编译的，内部用了 Files.readString / List.of /
        // Optional.isEmpty / Map.of 等 API —— 这些在 Android 12 (API 31) 上
        // 都不存在（readString 要 API 33）。不开脱糖，JGit 在旧机上第一次调用
        // 就抛 NoSuchMethodError/NoClassDefFoundError，表现就是「点开始使用闪退」。
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_module",
                "/OSGI-INF/**",
                "/plugin.properties",
                "META-INF/versions/9/module-info.class",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Git 同步层：JGit 嵌入式服务端 + 客户端 + SSH 身份
    implementation(libs.jgit)
    implementation(libs.jgit.http.server)
    implementation(libs.jgit.ssh.jsch)
    // JGit 的 GitServlet 继承自 jakarta.servlet.http.HttpServlet，需要 API 类才能编译与运行
    implementation(libs.jakarta.servlet.api)

    // 核心库脱糖运行时（配合 isCoreLibraryDesugaringEnabled）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

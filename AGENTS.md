# Kitchen Notes — 家庭菜谱管理器

局域网内 git 同步的家庭菜谱 App。拍照/截图/文字 → DeepSeek 生成结构化菜谱 → 一家人在同一 WiFi 下双向同步。
应用内自带更新（读 GitHub Releases → 下载 APK → 拉起系统安装器）。

包名 `io.github.Calvariaa.kitchennotes`（**大写 C**，和 GitHub 用户名一致；改小写会和已有仓库名冲突的直觉无关，纯粹是账号名本身带大写）。
仓库：`git@github.com:Calvariaa/kitchen-notes.git`

## 快速命令

```bash
# 本机没有 gradle wrapper 的 distribution 时，直接用缓存里的：
GH="$HOME/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1"
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"

"$GH/bin/gradle" :app:compileDebugKotlin   # 只查编译错误（约 20–35s）
"$GH/bin/gradle" :app:assembleDebug        # 出 debug APK（约 1m45s）
"$GH/bin/gradle" :app:assembleRelease      # 出 unsigned release（约 2m）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release-unsigned.apk`（签名交给 CI）

只筛错误行：`... 2>&1 | grep -E "^e: |FAILURE|BUILD"`

## SDK / 版本

| 项 | 值 | 说明 |
|---|---|---|
| compileSdk / targetSdk | 37 | |
| minSdk | 26 | Android 8.0+；Android 12 实测支持 |
| JGit | 7.8.0.202609011348-r | **已迁到 jakarta.servlet** |
| Gradle | 9.3.1 | wrapper 已入库，`gradlew` 是 100755 |
| JDK | 21 | |

版本号无文件：`versionName` 由 `git describe --tags` 推导（tag 后无提交 → 该 tag；有提交 → 抬 patch 加 `-dev.N+g<sha>`），
`versionCode` = HEAD 提交日期 `yyyyMMdd`。可用 `-Pkitchennotes.versionName=` / `-Pkitchennotes.versionCode=` 覆盖。

## 架构

`AppGraph` 是手写服务定位器（不用 Hilt：5 个屏 8 个服务，KSP 在 AGP 9 上的坑不值当）。
`DishViewModel` 持有全部 StateFlow，UI 只 collect。

```
app/src/main/java/io/github/Calvariaa/kitchennotes/
├── AppGraph.kt             服务定位器
├── KitchenNotesApp.kt      Application（logcat 里的 android:name）
├── MainActivity.kt         ComponentActivity + enableEdgeToEdge
├── ai/
│   ├── DeepSeekClient.kt   OpenAI 兼容 chat/completions，支持视觉
│   ├── RecipePrompt.kt     内置提示词（结构化 JSON schema）
│   └── RecipeAssembler.kt  JSON → Recipe，字段别名容错
├── data/
│   ├── Recipe.kt           菜谱模型
│   ├── RecipeRepository.kt 一菜一文件读写 + 内容散列图片存储 + Paths
│   ├── SettingsStore.kt    设置 / 身份 / 家庭组，JSON 文件
│   └── ImageIngestor.kt    压缩 + EXIF 方向校正
├── sync/
│   ├── GitRepo.kt          工作区仓库初始化、commitAll、冲突仲裁
│   ├── GitServerHost.kt    主机：HttpServer + 4 个端点
│   ├── MiniGitHandler.kt   HttpExchange → jakarta.servlet 桥接
│   ├── SyncClient.kt       成员：fetch/merge/push；HostRepository
│   ├── SyncResult.kt       同步结果的结构化事实（SyncPart / SyncError）
│   └── LanDiscovery.kt     /24 网段 TCP 扫活
├── update/
│   ├── UpdateChecker.kt    GitHub Releases 查询 + 版本比较 + APK 下载/校验
│   └── UpdateInstaller.kt  FileProvider + ACTION_VIEW 拉起系统安装器
└── ui/
    ├── KitchenNotesAppUi.kt  NavHost + 主题 + 语言覆盖
    ├── DishViewModel.kt      全部状态
    ├── i18n/                 AppLanguage / UiText（资源 id + 参数）
    ├── components/RecipeCard.kt
    └── theme/                8 套配色 Palette.kt + Theme.kt
```

## 存储布局

```
filesDir/store/
  repo/                        git 工作区
    dishes/<uuid>.json         一菜一文件（合并粒度 = 文件）
    images/yyyy/MM/<sha8>.jpg  内容散列命名，天然去重
    .git/
  grocery.git/                 主机裸仓库（成员 push 到这里）
  updates/                     下载下来的更新 APK（装完删）
  settings.json  identity.json  family.json
```

## 同步模型

- 主机：`HttpServer` 在 7411 端口同时提供 `/grocery.git`（smart HTTP）、`/api/info`、`/api/join`、`/api/leave`。
- 成员：`fetch → 本地 commit → merge（冲突按 updatedAt 取新） → push`。
- 主机自己编辑时：commit 后 `pushWorkspaceToBare`；成员 push 后 JGit 的 `postReceive` 钩子后台 `pullBareIntoWorkspace`。
- **主机和成员走两条不同代码路径**：主机不能走 `SyncClient.sync` 的网络分支，会自己连自己把仓库锁死。

## i18n

- 两套资源：`values/strings.xml`（英文，默认）与 `values-zh-rCN/strings.xml`。**两边条目数必须一致**，加字符串必须同时加。
- 切换语言不用 AppCompat：`ProvideAppLocale` 覆盖 `LocalContext` / `LocalConfiguration`，即时生效、不重建 Activity。
  代价是「跟随系统」模式下才让 Compose 正常响应系统语言变化。
- **ViewModel 不能存 String**。所有会展示的文案是 `UiText`（资源 id + 参数），渲染时才解析 ——
  否则用户切了语言，之前生成的提示还是旧语言。含数据和句子的混合文案（「已加入 X」）用带占位符的资源。
- 需要区分颜色/分支的结果不要靠比对文案前缀（`startsWith("连接成功")` 一翻译就废），
  ViewModel 另给一个布尔/枚举字段（如 `aiTestOk`）。
- 数据层不拼人话：`Recipe.ingredientPreviewParts()` 只返回「前 N 个名字 + 剩余数量」，
  「等 N 种」/「+N more」由 UI 用资源拼。

## 应用内更新

- 来源：`https://api.github.com/repos/<updateRepo>/releases/latest`（可在设置里改成自己的 fork）。
  仓库还没有 Release 时该接口返回 **404**，这是正常状态，当「已是最新」处理。
- 只认 Release assets 里的 `.apk`；同名的 `<apk>.sha256` 是 CI 一起传的校验文件。
  有就严格比对 SHA-256，没有就退化成「文件头必须是 `PK`」，不因缺校验文件拒绝安装。
- 安装走 `FileProvider` + `Intent.ACTION_VIEW`（`application/vnd.android.package-archive`），
  不用 `PackageInstaller` Session：Session 的 `PendingIntent` 回执在各家 ROM 上不稳，失败态难区分。
- **Android 8+ 必须先有 `REQUEST_INSTALL_PACKAGES` 授权**。未授权时不要直接弹安装
  （会得到「解析包时出现问题」），要 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 引导用户开。
- 文件放 `files/store/updates/`，不放 cacheDir —— cache 会被系统清掉，用户点安装时文件已没了。
  对应的 FileProvider 路径在 `res/xml/file_paths.xml` 里（`updates`）。

## CI / 发版

`.github/workflows/android.yml`：push 到 master / 推 `v*` tag 时构建 release，
用缓存的 debug keystore 重签（`app/build.gradle.kts` 的 release **故意不配** `signingConfig`，AGP 只吐 unsigned），
`zipalign` + `apksigner` 后用 `output-metadata.json` 里的真实版本号命名 `<applicationId>-<versionName>.apk`，
再生成 `.sha256`。推 tag 时创建 Release 并用 `.github/scripts/changelog.py` 追加按 conventional commit 分组的清单。

发版：`git tag v0.1.0 && git push --tags`。

## 踩过的坑（改这块之前先读）

1. **JGit 7.x 是 jakarta.servlet，不是 javax.servlet。**
   按 javax 写 `MiniGitHandler` 会得到成片 `Unresolved reference 'servlet'`。
   依赖：`jakarta.servlet:jakarta.servlet-api:6.1.0`（provided 才行，但 Android 上要打进包）。

2. **jakarta.servlet 6.1 比 5.x 多三个抽象方法**：
   `getRequestId()`、`getProtocolRequestId()`、`getServletConnection()`。不实现就是 abstract 未实现。

3. **`RepositoryResolver` 是 `open(C, String)` 两参数**，不是 `open(C)`。

4. **`FetchResult` 不是集合**，遍历用 `.advertisedRefs`，不是 `.mapNotNull {}`。

5. **`RefUpdate` 用 setter**：`setNewObjectId()` / `setForceUpdate()`，属性赋值 `newObjectId = x` 会报 `'val' cannot be reassigned`。

6. **`sendResponseHeaders(status, 0)` 才是 chunked**。git 协议响应长度不可知，写死 `-1` 会截断。

7. **ViewModel 里别让属性名与 setter 撞 JVM 签名**：
   `var query` + `fun setQuery(String)` → `Platform declaration clash`。已改名为 `searchQuery`。

8. **空仓库 merge 会失败**（无共同祖先）。首次加入走 `adoptRemote`：直接对齐远端分支。

9. **`CheckoutCommand.setStartPoint()` 在空仓库不稳**，改用 `updateRef` + `checkout` + `reset --hard`。

10. **Compose 里不要在组合过程中导航**：`if (draft != null) onGenerated()` 会抛
    "composing while navigating"。必须包进 `LaunchedEffect(key)`。

11. **`remember` 不能装普通 `mutableSetOf`**：它不触发重组。要 `mutableStateListOf`（详情页的材料勾选）。

12. **拍照的目标 Uri 必须是 Compose 状态**（`remember { mutableStateOf<Uri?>(null) }`），
    用文件顶层的 `var` 会在重组/返回栈之间串台。

13. **Release assets API 里 `size` 是数字，不是字符串**；`JsonPrimitive.content` 取得到，但别 `as? JsonObject` 硬转整个数组。

14. **JGit 7.x 必须开 core library desugaring。** 它用 Java 11 编译，内部调
    `Files.readString` / `Files.writeString` / `Files.mismatch`，Android 12 (API 31) 上没有
    （`readString` 要 API 33）。不开脱糖 → 首次建仓库就 `NoSuchMethodError`，表现是
    **新装后点「开始使用」直接闪退**，且只有旧系统复现（模拟器 API 36 看不出来）。
    配置：`isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")`。
    验证办法：反编译 APK 的 dex 查 `Ljava/nio/file/Files;.readString` 是否还在。

15. **`viewModelScope.launch` 里的未捕获异常 = 进程闪退。** 它没有
    `CoroutineExceptionHandler`，异常直接走默认处理器。凡是用户点了就会触发的路径
    （引导、导入、同步、下载），都要自己 `try/catch` 并把原因变成 UI 可见的文案。

16. **git 仓库初始化不能当成进入 App 的前提。** 列表读的是 `repo/dishes/*.json`，
    不依赖 `.git`。`ensureIdentity` 里 `runCatching` 掉 `ensureInitialized`，
    失败只记 log —— 否则一次 JGit 抽风就把用户永久挡在引导页。

## 权限

`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`REQUEST_INSTALL_PACKAGES`（应用内更新）。
都不需要运行时弹窗，但**未知来源安装要去系统设置手动开**，代码里用 `canRequestPackageInstalls()` 判断。

拍照走 `FileProvider`（authority `${applicationId}.fileprovider`），选图走系统相册选择器。
**不用** `WifiManager.getConnectionInfo()` 拿网段 —— Android 12 之后受隐私限制，直接扫 `/24` 更可靠。

## 约定

- 提交信息格式 `recipe:add|edit|delete <id>` / `recipe:sync <ts>` / `merge: ...`；
  给 CI 的发布说明用的门面提交请走 conventional commit（`feat:` / `fix:` / `ui:` …）
- 删除是逻辑删除（写 `deleted: true` 墓碑），不删文件
- 图片按 SHA-256 前 8 字节命名，重复上传不新增对象
- 代码注释解释「为什么」，不复述「做了什么」

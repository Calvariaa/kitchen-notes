# DishPlanner — 家庭菜谱管理器

局域网内 git 同步的家庭菜谱 App。拍照/截图/文字 → DeepSeek 生成结构化菜谱 → 一家人在同一 WiFi 下双向同步。

## 快速命令

```bash
# 本机没有 gradle wrapper 的 distribution 时，直接用缓存里的：
GH="$HOME/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1"
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"

"$GH/bin/gradle" :app:compileDebugKotlin   # 只查编译错误（约 15–35s）
"$GH/bin/gradle" :app:assembleDebug        # 出 APK（约 1m45s）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

只筛错误行：`... 2>&1 | grep -E "^e: |FAILURE|BUILD"`

## SDK / 版本

| 项 | 值 | 说明 |
|---|---|---|
| compileSdk / targetSdk | 37 | |
| minSdk | 26 | Android 8.0+；Android 12 实测支持 |
| JGit | 7.8.0.202609011348-r | **已迁到 jakarta.servlet** |
| Gradle | 9.3.1 | |
| JDK | 21 | |

## 架构

`AppGraph` 是手写服务定位器（不用 Hilt：5 个屏 6 个服务，KSP 在 AGP 9 上的坑不值当）。
`DishViewModel` 持有全部 StateFlow，UI 只 collect。

```
app/src/main/java/com/dishplanner/
├── AppGraph.kt             服务定位器
├── ai/
│   ├── DeepSeekClient.kt   OpenAI 兼容 chat/completions，支持视觉
│   ├── RecipePrompt.kt     内置提示词（结构化 JSON schema）
│   ├── RecipeAssembler.kt  JSON → Recipe，字段别名容错
│   └── (RecipeJsonRepair 在 RecipeAssembler 同文件)
├── data/
│   ├── Recipe.kt           菜谱模型
│   ├── RecipeRepository.kt 一菜一文件读写 + 内容散列图片存储
│   ├── SettingsStore.kt    设置 / 身份 / 家庭组，JSON 文件
│   └── ImageIngestor.kt    压缩 + EXIF 方向校正
├── sync/
│   ├── GitRepo.kt          工作区仓库初始化、commitAll、冲突仲裁
│   ├── GitServerHost.kt    主机：HttpServer + 4 个端点
│   ├── MiniGitHandler.kt   HttpExchange → jakarta.servlet 桥接
│   ├── SyncClient.kt       成员：fetch/merge/push；HostRepository
│   └── LanDiscovery.kt     /24 网段 TCP 扫活
└── ui/
    ├── DishApp.kt          NavHost + 主题装配
    ├── DishViewModel.kt    全部状态
    ├── screens/            列表/详情/新建/草稿/设置/加入/引导
    └── theme/              8 套配色 Palette.kt + Theme.kt
```

## 存储布局

```
filesDir/store/
  repo/                        git 工作区
    dishes/<uuid>.json         一菜一文件（合并粒度 = 文件）
    images/yyyy/MM/<sha8>.jpg  内容散列命名，天然去重
    .git/
  grocery.git/                 主机裸仓库（成员 push 到这里）
  settings.json  identity.json  family.json
```

## 同步模型

- 主机：`HttpServer` 在 7411 端口同时提供 `/grocery.git`（smart HTTP）、`/api/info`、`/api/join`、`/api/leave`。
- 成员：`fetch → 本地 commit → merge（冲突按 updatedAt 取新） → push`。
- 主机自己编辑时：commit 后 `pushWorkspaceToBare`；成员 push 后 JGit 的 `postReceive` 钩子后台 `pullBareIntoWorkspace`。
- **主机和成员走两条不同代码路径**：主机不能走 `SyncClient.sync` 的网络分支，会自己连自己把仓库锁死。

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

## 权限（全部是普通权限，无运行时申请）

`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`

拍照走 `FileProvider`（authority `${applicationId}.fileprovider`），选图走系统相册选择器。
**不用** `WifiManager.getConnectionInfo()` 拿网段 —— Android 12 之后受隐私限制，直接扫 `/24` 更可靠。

## 约定

- 提交信息格式 `recipe:add|edit|delete <id>` / `recipe:sync <ts>` / `merge: ...`
- 删除是逻辑删除（写 `deleted: true` 墓碑），不删文件
- 图片按 SHA-256 前 8 字节命名，重复上传不新增对象
- UI 全部简体中文；代码注释解释「为什么」，不复述「做了什么」

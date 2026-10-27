# Kitchen Notes — 家庭菜谱管理器

一个只在自家局域网里跑的菜谱 App。拍张照或者贴一段文字，DeepSeek 把它整理成
带用量的分步菜谱；一家人的手机在同一 WiFi 下互相同步，没有服务器、没有账号、
没有云端。

Kotlin + Jetpack Compose 原生界面，不是 WebView 套壳。手机竖屏和平板都能用。

## 能做什么

- **拍照 / 截图 / 贴文字生成菜谱**：给模型一段描述或几张成品图，它返回结构化 JSON
  （菜名、简介、标签、份量、用时、材料带用量、分步带火候与时长），本地再逐字段容错装配
- **生成前先看草稿**：AI 的结果先进草稿页，可以改标题、删材料、调步骤、换封面，确认后才落库
- **菜谱库**：搜索、按标签筛、按最近做过 / 名称 / 收藏排序，卡片带封面缩略图
- **菜单**：早 / 午 / 晚三餐一天一条记录，勾菜进某一餐、指定主食（米 / 面 / 馒头）、
  写备注，材料自动汇总并列出需要的图片
- **打分与「做过的记录」**：给每道菜在这次饭里的表现打 1–5 星，也可以不打；详情页能翻到
  这道菜历史上做过几次、每次几分
- **全互联局域网同步**：每台设备都是一个节点，两两直连，没有主机、没有单点。
  详见下面的「同步模型」
- **多语言**：中文 / English / 跟随系统，切换即时生效，不重建 Activity
- **8 套配色 + 深色模式**：赤陶、橄榄、藏红、抹茶、陶土、焦糖、竹青、可可，
  可选跟随系统壁纸取色（Android 12+）
- **应用内更新**：设置里读 GitHub Releases 比对版本，比当前新就下载 APK 并拉起系统安装器

## 同步模型：全互联 P2P

**没有主机，没有「成员」，每台装了 App 的设备都是同一种节点。**

```
        ┌──────────┐
        │  手机    │
        └────┬─────┘
             │  ← 两两直连，各自持有完整仓库
        ┌────┴─────┐
        │  平板    │
        └────┬─────┘
             │
        ┌────┴─────┐
        │  客厅旧机 │
        └──────────┘
```

- **身份 = SSH 密钥**。设备第一次启动时生成一对 RSA-2048，私钥永不出设备。
  `nodeId = "SHA256:" + base64(sha256(公钥))` 就是它的身份证。
- **每台都在 7411 上跑同一个服务**：`/node.git`（smart HTTP）、`/mesh/join`、
  `/mesh/info`、`/mesh/hello`。谁都不是「服务端」，谁都能被别人连。
- **每台都有自己的裸仓库 `node.git`**，暴露给对端 fetch/push。双方都是完整历史，
  不是单边镜像 —— 所以任何一台掉线，其余照常互相同步。
- **家庭密钥**是成员资格的根。`HMAC(secret, "kn-group-tag")` 用来快速认出「是不是一家」，
  `HMAC(secret, "kn-invite")` 派生出 8 位加入码（形如 `DTHW-HRBK`）。
  加入码是**派生**的，不是存的 —— 换设备只要 secret 还在，码就不变。
- **加入流程**：新设备扫 `/24` 网段找到邻居 → `POST /mesh/join` 带上自己的公钥和加入码 →
  对方验码通过后，用**加入者的公钥**把 secret 封好（RSA-OAEP-SHA256）回传，
  并附上自己的 nodeId / 公钥 / 端口和它认识的名册。
- **请求鉴权三层**：① `SHA256withRSA` 验签，证明持有私钥；② 公钥必须在本地名册里，
  证明是这个家的；③ 时间戳在 5 分钟内且 nonce 未用过，挡重放。
  头格式 `Authorization: Kn1 <nodeId> <timeMs> <nonce> <sig>`。

同步本身是真正的 git：`fetch → merge → push`，冲突按 `updatedAt` 取新。
菜谱是一菜一文件、菜单是一餐一文件，所以合并粒度就是文件，日常几乎不会撞。

## 数据都放哪

全部在 App 私有目录里，不申请存储权限，不联网除了 DeepSeek 和 GitHub：

```
filesDir/store/
  repo/                        git 工作区
    dishes/<uuid>.json         一菜一文件
    menus/<uuid>.json          一餐一文件
    images/yyyy/MM/<sha8>.jpg  按内容散列命名，重复上传不新增
  node.git/                    本机裸仓库（对端 fetch/push 的对象）
  identity.json                身份（SSH 密钥对、名字、邮箱）
  family.json                  家庭组（名字 + secret）
  peers.json                   对端名册（nodeId、公钥、地址、最后见到时间）
  settings.json                设置
  updates/                     下载下来的更新包，装完删
```

删除是逻辑删除（写 `deleted: true` 墓碑），不真删文件 —— 否则对端的
「删除」提交和本地的「还有这条」会打成内容冲突，而不是一个干净的决定。

## 构建

用 Android Studio 自带的 JDK 21 就行：

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"

.\gradlew.bat :app:compileDebugKotlin   # 只查编译错误，快
.\gradlew.bat :app:assembleDebug        # 产物在 app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:assembleRelease      # 吐 unsigned，签名交给 CI
```

最低 Android 8.0（minSdk 26），compileSdk / targetSdk 37，Gradle 9.3.1，Kotlin 2.4.21。
JGit 6.5.0、OkHttp 5.5.0、Compose BOM 2026.09.00。

版本号不用手动改：`versionName` 由 `git describe --tags` 推导，
`versionCode` 是「HEAD 提交日期 × 100 + 当天第几次发版」。发版就是
`git tag v0.6.0 && git push --tags`，CI 会构建、重签、命名、上传 Release。

> **JGit 锁在 6.5，不要往上升。** 7.x 起 `org.eclipse.jgit.util.IO.readFully` 改用
> `InputStream.readNBytes`，那是 Java 9 的 API、Android 到 **API 33** 才有。
> 更坑的是 core library desugaring **不管这个**：官方 `desugar_jdk_libs` 2.1.5 的
> `DesugarInputStream` 只 backport 了 `transferTo`，没有 `readNBytes`
> （唯一的 `desugar_jdk_libs_nio` 变体也不含它）。
>
> 表现是 Android 12 上点「开始使用」后**创建家庭失败**，logcat 里是
> `NoSuchMethodError: No virtual method readNBytes(I)[B in class SilentFileInputStream`
> ← `IO.readFully` ← `FileBasedConfig.load` ← 任何一个 JGit 调用。因为读 config 是最早
> 经过的路径，等于整个同步层在 12 上全废。6.5 是我们验证过的最后一个干净的版本。

## 代码结构

```
app/src/main/java/io/github/Calvariaa/kitchennotes/
├── AppGraph.kt              手写服务定位器（10 屏十来个服务，不值当上 Hilt）
├── ai/
│   ├── DeepSeekClient.kt    OpenAI 兼容 chat/completions，支持视觉输入
│   ├── RecipePrompt.kt      内置提示词（结构化 JSON schema）
│   └── RecipeAssembler.kt   JSON → Recipe，字段别名容错
├── data/
│   ├── Recipe.kt            菜谱模型
│   ├── RecipeRepository.kt  一菜一文件读写 + 内容散列图片存储 + Paths
│   ├── Menu.kt              菜单模型（餐次 / 主食 / 打分 / 排序）
│   ├── MenuRepository.kt    一餐一文件 + 打分 + 做过的记录反查
│   ├── Identity.kt          UserIdentity / MeshGroup / Peer
│   ├── SettingsStore.kt     设置 / 身份 / 家庭组 / 名册，JSON 文件
│   └── ImageIngestor.kt     压缩 + EXIF 方向校正
├── sync/                    ← 全互联 P2P，没有主机/成员之分
│   ├── MeshServer.kt        本机节点服务：/node.git + /mesh/*
│   ├── MiniHttpServer.kt    ServerSocket + 手写 HTTP/1.1（含 gzip 请求体）
│   ├── MiniGitHandler.kt    HttpCall → JGit UploadPack / ReceivePack
│   ├── MeshSync.kt          与每个对端 fetch → merge → push
│   ├── MeshRepo.kt          本机裸仓库 node.git：镜像 / 回灌 / 旧仓库迁移
│   ├── MeshGroupManager.kt  创建 / 加入 / 改名 / 退出家庭
│   ├── MeshApi.kt           /mesh/hello、/mesh/join、/mesh/info 客户端
│   ├── MeshAuth.kt          给请求打 Kn1 签名头
│   ├── MeshCrypto.kt        节点身份、验签、家庭密钥派生、OpenSSH 密钥解析
│   ├── MeshWire.kt          节点间 JSON 报文
│   ├── PeerRegistry.kt      对端名册 + MeshGuard（验签 / 成员资格 / 防重放）
│   ├── GitRepo.kt           工作区仓库初始化、commitAll、冲突仲裁
│   ├── SyncResult.kt        同步结果的结构化事实
│   └── LanDiscovery.kt      /24 网段 TCP 扫活
├── update/
│   ├── UpdateChecker.kt     GitHub Releases 查询 + 版本比较 + APK 下载校验
│   └── UpdateInstaller.kt   FileProvider + ACTION_VIEW 拉起系统安装器
└── ui/
    ├── KitchenNotesAppUi.kt  NavHost + 主题 + 语言覆盖 + 抽屉外壳
    ├── DishViewModel.kt      全部状态（唯一真值源）
    ├── MenuFormat.kt         日期 / 餐次 / 标题的本地化渲染
    ├── i18n/                 UiText（资源 id + 参数）
    ├── screens/              10 个页面
    └── theme/                8 套配色
```

数据流很直白：`Screen → DishViewModel → Repository / MeshXxx`，
ViewModel 持有全部 StateFlow，UI 只 collect。

两个刻意的设计约束：

- **ViewModel 里不存 String**。所有会展示的文案是 `UiText`（资源 id + 参数），
  渲染时才解析 —— 否则用户切了语言，之前生成的提示还是旧语言。
- **每个用户动作都要显式 `commitAndPush`**。`commitAll` 虽然整仓提交，但只在
  定时同步和启动时跑。新建菜谱、改菜单、打分这些入口漏掉任何一个，那类改动就
  只落在本地磁盘、不会进 git，表现为「在另一台设备上凭空消失」。

## 已知问题

- 同步层是全新重写的（v0.5.0）。**`family.json` 结构和旧版不兼容**：
  从主机模式升级上来会落到「未加入家庭」状态，需要重新创建或加入一次。
  菜谱数据不会丢。
- **v0.6.0 修的 Android 12 崩溃**：v0.5.0 在 Android 12（API 31）上创建家庭会失败，
  原因是 JGit 7.x 的 `readNBytes`（见上面构建节的说明）。v0.6.0 把 JGit 降到 6.5 修掉了。
  v0.5.0 的用户在 12 上装不上的话，直接下 v0.6.0。
- 局域网要求所有设备在同一个网段。家用路由器基本都是 /24，但如果开了 AP 隔离，
  设备互相扫不到 —— 这种情况需要关掉隔离。
- 图片是原图压缩到 1400px 后进 git 的。菜谱多了之后仓库会变大，
  同步速度取决于第一次拉取。
- 生成质量取决于你用的模型和提示词。DeepSeek 的视觉模型对中餐成品图的识别还行，
  但分量估算经常偏，草稿页最好过一眼再存。

## 说明

自家用的东西，顺便开源。菜谱数据全在你自己的设备上，App 除了你配置的模型接口
和 GitHub 的版本查询之外不往外发任何东西。

# android-tools · 录屏助手

> 工程名 `android-tools`，应用名「录屏助手」——界面是一个可扩展的「工具箱」，屏幕录制是其中的第一个工具。

Android 屏幕录制应用：可以录制**整个屏幕**，也可以在 Android 14+ 上录制**单个应用**；录制参数（帧率、分辨率、码率、编码格式、声音、保存位置等）全部可配置。

技术栈：Kotlin · Jetpack Compose (Material 3) · MediaProjection · MediaCodec · MediaMuxer · Gradle（Kotlin DSL）。

---

## 功能

**界面**
- 首页是「工具箱」：屏幕录制作为主卡片，录制中直接显示计时 / 暂停 / 停止；下方「更多工具」网格由
  `ui/ToolRegistry.kt` 驱动，后续新增工具只需注册一条 `ToolSpec`，首页布局不用改
- 底部浮动胶囊导航（工具 / 文件 / 设置），选中项展开为「图标 + 文字」，录制中在「工具」上显示红点
- 深 / 浅色主题跟随系统，状态栏、导航栏图标自动适配
- 录制文件列表带**首帧缩略图**、时长角标，播放 / 分享 / 删除收进「更多」菜单
- 设置页按功能分组、每项带图标，顶部应用信息卡显示当前版本

**录制**
- 整屏录制 / 单个应用录制（Android 14+ 系统级单应用捕获）
- H.264 / H.265 硬件编码，MediaCodec 表面输入
- 帧率 15 / 24 / 30 / 60 fps（**固定帧率**，成片帧率与设置一致，动画/游戏场景不会超过设定值）
- 分辨率 原始 / 1080p / 720p / 480p（按编码器支持能力取最接近原生的尺寸）
- 画质预设 + 自定义码率（1–50 Mbps，可自动计算）
- 声音：不录 / 麦克风 / 内部音频（AudioPlaybackCapture）
- 音频码率 64–320 Kbps

**录制控制**
- **悬浮控制按钮**：录制时悬浮在其他应用之上，点击暂停/继续，长按停止，可拖动；红色=录制中，灰色=已暂停（需「显示在其他应用上层」权限）
- 前台服务 + 通知栏「暂停 / 继续 / 停止」，退出应用后继续录制
- 录制前倒计时 0–10 秒
- 最长录制时长限制（0–60 分钟，到点自动停止）
- 可选的「离开目标应用后自动停止」（需要「使用情况访问」权限）
- 录制中旋转屏幕时，画面按原始方向等比缩放并保留黑边，视频分辨率保持不变

**文件管理**
- 保存位置：相册 `Movies/ScreenRecorder`、SAF 自定义文件夹、应用专属目录
- 文件名前缀可配置，默认 `ScreenRecorder_20260101_120000.mp4`
- 应用内列表：缩略图 / 播放 / 分享 / 删除

---

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 最低系统 | Android 10（API 29） |
| 单应用录制 | Android 14（API 34）及以上 |
| JDK | 17 及以上（本项目在 JDK 21 上验证） |
| Android SDK | Platform 35、Build-Tools 35.0.1 |
| Gradle | 8.11.1（仓库自带 wrapper） |
| AGP / Kotlin | 8.7.3 / 2.0.21 |

## 构建

工程根目录即 `android-tools`（Gradle 根项目名 `android-tools`，应用模块为 `:app`）。

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/android-tools-1.1.1-debug.apk`

产物文件名统一带版本号（`android-tools-<versionName>-<buildType>.apk`，未签名的 release 追加
`-unsigned`），版本号取自 `defaultConfig.versionName`，改版本号后文件名自动跟随：

| 命令 | 产物 |
| --- | --- |
| `:app:assembleDebug` | `app/build/outputs/apk/debug/android-tools-1.1.1-debug.apk` |
| `:app:assembleRelease` | `app/build/outputs/apk/release/android-tools-1.1.1-release-unsigned.apk` |

安装：

```bash
adb install -r app/build/outputs/apk/debug/android-tools-1.1.1-debug.apk
```

> **网络提示**：`gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 当前指向腾讯云镜像
> （`https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`），因为构建环境无法直连
> `services.gradle.org`。如果你的网络可以访问官方地址，改回
> `https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip` 即可。
>
> `local.properties` 里的 `sdk.dir` 需要指向本机 Android SDK 路径（该文件不提交到版本库）。

---

## 使用

1. 打开应用，首次录制时按提示授予「通知」「麦克风」权限。
2. 在「设置」中选择录制范围、分辨率、帧率、码率、声音来源与保存位置。
3. 回到「工具」页，点击「屏幕录制」卡片里的录制按钮；系统会弹出屏幕捕获授权（Android 14+ 可在弹窗中选择「单个应用」）。
4. 录制过程中可以在卡片内、悬浮按钮或通知栏暂停 / 继续 / 停止；停止后会自动跳到「文件」页。

### 新增工具（扩展点）

界面从一开始就按「多个工具」设计，新增工具不需要动首页布局：

1. 在 `ui/ToolRegistry.kt` 的 `ToolId` 里加一项；
2. 在 `ToolRegistry.tools` 里加一条 `ToolSpec`（标题、说明、图标、强调色；占位用 `ToolStatus.PLANNED`）；
3. 可用的工具，在 `ui/RecorderApp.kt` 的 `AppTab.TOOLS` 分支里挂上它自己的界面（或按 `tool.id` 分发）。

首页的「更多工具」网格、工具卡片配色、以及「即将推出」提示都会自动生效。

### 单应用录制说明

Android 的系统限制决定了「录制哪个应用」只能由**系统弹窗**选择，第三方应用无法强制指定。
因此本应用的做法是：

- 在设置里选择「单个应用」，此时申请屏幕捕获时会使用 `MediaProjectionConfig.createConfigForUserChoice()`，
  系统弹窗中会出现「单个应用」选项，由用户选择被录制的应用；
- 设置里的「目标应用」只用于界面提示和「离开目标应用后自动停止」判断，不会改变系统行为。

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 录制期间常驻前台服务 |
| `POST_NOTIFICATIONS` | 通知栏控制按钮（Android 13+） |
| `RECORD_AUDIO` | 麦克风或内部音频录制 |
| `WAKE_LOCK` | 长时间录制时保持 CPU 唤醒 |
| `PACKAGE_USAGE_STATS` | 可选的「离开目标应用后自动停止」，需用户在系统设置中手动授权 |
| `<queries>` (LAUNCHER) | 列出可录制的应用 |

屏幕捕获不需要在清单中声明权限，但每次录制都需要用户通过系统弹窗授权（授权仅在本次录制有效）。

---

## 架构

```
app/src/main/java/com/screenrecorder/app/
├── MainActivity.kt                 Compose 入口
├── ScreenRecorderApp.kt
├── core/
│   ├── RecorderSettings.kt         所有可配置项 + 分辨率/码率计算
│   ├── SettingsRepository.kt       SharedPreferences + StateFlow
│   └── RecorderState.kt            录制状态、控制器、服务命令
├── capture/
│   ├── ScreenRecorderService.kt    前台服务、通知栏、MediaProjection 生命周期
│   ├── RecordingEngine.kt          管线编排、暂停、旋转、时长/前台应用限制
│   ├── FloatingControl.kt          悬浮控制按钮（点击暂停/长按停止/拖动/状态配色）
│   ├── GlFramePump.kt              GL 重采样：固定帧率（CFR）、丢帧/补帧
│   ├── VideoEncoder.kt             MediaCodec 表面输入 + 输出线程
│   ├── AudioCapture.kt             AudioRecord → AAC
│   ├── Mp4Muxer.kt                 多轨就绪后启动、起步样本缓存、单调时间戳保护
│   └── TimelineClock.kt            统一音视频时间轴与暂停补偿
├── storage/
│   ├── RecordingStorage.kt         MediaStore / SAF / 私有目录输出
│   └── RecordingsRepository.kt     录制文件列表、播放、分享、删除
├── apps/
│   ├── InstalledApps.kt            已安装应用列表
│   └── ForegroundAppMonitor.kt     前台应用检测（UsageStats）
├── ui/
│   ├── RecorderApp.kt              应用外壳：底部导航、权限/授权流程、倒计时
│   ├── Theme.kt                    配色（深/浅色）、字体、形状、工具强调色
│   ├── ToolRegistry.kt             工具箱注册表（新增工具在此登记）
│   ├── ToolHubScreen.kt            首页：录制主卡片 + 更多工具 + 最近录制
│   ├── RecordingsScreen.kt         录制文件列表
│   ├── RecordingCard.kt            录制文件卡片（缩略图 + 更多菜单）
│   ├── VideoThumbnail.kt           视频首帧缩略图
│   ├── SettingsScreen.kt           设置页
│   ├── AppPickerDialog.kt          目标应用选择
│   ├── AppIcon.kt                  应用图标（带缓存）
│   └── Components.kt               通用组件：卡片、分组、选择/滑块对话框、空状态
└── util/                           格式化、显示尺寸
```

### 关键实现细节

- **前台服务优先**：Android 14 起必须先 `startForeground(type = mediaProjection)` 再调用
  `getMediaProjection()`，否则抛 `SecurityException`；`MediaProjection.Callback` 也必须在
  `createVirtualDisplay()` 之前注册。
- **时间轴**：`VirtualDisplay` 输出的帧时间戳是 `System.nanoTime()` 绝对值，音频时间戳由
  `AudioRecord` 采样数推算，二者共用一个 `TimelineClock`；暂停时把时间原点整体后移，避免音画不同步。
- **封装器**：`MediaMuxer` 只能在 `start()` 之前 `addTrack()`，而音视频轨的格式是异步到达的，
  因此 `Mp4Muxer` 会缓存起步样本，等所有轨道就绪（或 1.5s 超时降级为单轨）后统一写入。
- **帧率（CFR）**：屏幕镜像按刷新率（60/90/120 Hz）出帧，且画面静止时不出帧，直接送编码器会得到
  帧率忽高忽低的 VFR 文件。`GlFramePump` 用 GL 在虚拟显示与编码器之间按 1/fps 的固定节奏取最新
  一帧重绘，因此成片帧率恒等于设置值（静止画面重复上一帧）；手机解码/相册里显示的帧率不再跳动。
  如果设备编码性能不足以撑住设定帧率，实际帧率会随之下降，但不会超过设置值。
- **旋转**：Android 14 起一个 `MediaProjection` 只允许创建一个 `VirtualDisplay`，编码器也无法
  中途改分辨率，因此整个录制使用固定帧尺寸；旋转时系统镜像会等比缩放到该尺寸（上下/左右留黑边）。
- **采集尺寸 1:1**：`VirtualDisplay` 必须按**物理全屏尺寸**创建（`maximumWindowMetrics`，包含状态栏/
  导航栏）。早期版本用 `currentWindowMetrics` 再按 16 像素对齐，会把 1440×3200 的画面压成
  1440×3088 / 1072×2400 —— 这种非整数缩放会让整帧发虚，且后续提高码率也无法挽回。现在按真实编码器
  接受能力选择尺寸（`EncoderSupport`，由真正的编码器实例决定，必要时才降级），保证镜像不被重采样。
- **码率单位**：`MediaFormat.KEY_BIT_RATE` 的单位是 **bit/s**，而设置里保存的是 Kbps。1.0.0 直接把
  Kbps 传给了编码器（例如 22118 而不是 22118000），编码器把它当成 ~22 kbps 并按最低档编码 ——
  这就是"设置最高分辨率、加大码率依然很糊"的根因。1.0.1 已修正为 `Kbps × 1000`，并可用
  `logcat | grep coded.bitrate` 验证编码器实际收到的值。

---

## 已验证（1.1.1 界面）

在 Android 15（API 35，x86_64）模拟器上逐屏检查了重构后的界面（深色 + 浅色）：

| 场景 | 结果 |
| --- | --- |
| 首页工具箱 | 录制主卡片、配置摘要（分辨率/帧率/码率/声音/保存位置）、更多工具网格、最近录制均正常 |
| 录制中 / 已暂停 | 卡片切换为计时 + 文件名 + 暂停/继续/停止；状态徽章、计时与红点同步；底部「工具」出现录制红点 |
| 倒计时 | 3 秒倒计时圆形弹窗正常，随后弹出系统捕获授权 |
| 停止录制 | 自动跳到「文件」页并提示「已保存：xxx.mp4」，列表即时刷新 |
| 文件页 | 首帧缩略图、时长角标、大小/时间、更多菜单（播放/分享/删除）与删除确认均正常 |
| 空状态 | 文件页与首页「最近录制」的空状态布局正常 |
| 设置页 | 6 个分组、图标、版本徽章（v1.1.1）与各类选择/滑块/输入对话框均正常 |
| 目标应用 | 应用列表带真实图标、搜索、已选标记；选中后设置页回显应用名 |
| 深色模式 | 配色、卡片层次、状态栏/导航栏图标跟随主题；运行中切换深色模式图标即时刷新 |

## 已验证（1.0.1）

在 Android 15（API 35，x86_64）模拟器上做过端到端验证：

| 场景 | 结果 |
| --- | --- |
| 码率是否生效 | 1080p30「标准」→ 编码器收到 **7,776,000 bps**（修复前只有 7776 bps） |
| 采集分辨率 | 1080×2400 的屏幕录制出 **1080×2400**（1:1，不再被压成 1072×2400） |
| 画面清晰度 | 录制帧 vs 系统截屏：**SSIM 0.989 / PSNR 32.5 dB**（差异仅来自 H.264 编码） |
| 帧率 30（480p） | 482 帧 / 16.29 s = **29.6 fps**，与设置一致 |
| 帧率 60（480p） | 573 帧 / 10.04 s = **57 fps**（模拟器 CPU 编码瓶颈略低，真机硬件编码可跑满） |
| 麦克风声音 | AAC 44.1kHz 单声道，与视频轨时长差 < 0.1s |
| 暂停 / 继续 | 实际 13.36s、暂停 3.5s → 成片 9.87s，音画仍同步 |
| 录制中旋转 | 录制不中断，画面等比缩放并保留黑边（分辨率不变） |
| 单个应用 | 系统应用选择器选中「时钟」后，画面只包含该应用 |
| 通知栏 | 录制 / 暂停状态与「暂停/继续/停止」按钮刷新正常 |

## 已知限制

- 单应用录制依赖系统 UI（Android 14+），第三方应用无法指定被录应用；「目标应用」仅用于提示与自动停止。
- 内部音频只对允许被捕获的应用生效（DRM 内容、部分应用会静音），系统还会屏蔽通知音等。
- 录制中旋转屏幕不会改变视频分辨率，只会等比缩放并留黑边；需要竖屏/横屏视频请在开始前确定方向。
- 固定帧率意味着静止画面也会按设定帧率重复编码（VBR 下每帧只有几百字节），文件会比纯 VFR 略大。
- 性能不足的老设备可能跑不满 60fps，此时实际帧率会低于设置值。
- 悬浮按钮与其他悬浮窗一样会被录进画面；不需要时可在设置里关闭。
- 尚未实现：触摸轨迹、摄像头画面合成、剪辑、录制后合并/裁剪。

## 版本

`1.1.1` — 界面重构，对应需求 `需求/version-1.1.1.MD`：

- 首页改为**工具箱**结构：屏幕录制是渐变主卡片（录制中直接显示计时与暂停/停止），
  下方「更多工具」网格由 `ui/ToolRegistry.kt` 驱动 —— 后续新增工具只需在注册表里加一条，
  不用改首页布局
- 底部导航改为浮动胶囊样式（工具 / 文件 / 设置），选中项展开为「图标 + 文字」，录制中显示红点
- 录制文件列表新增**首帧缩略图**与时长角标，播放/分享/删除收进「更多」菜单，顶部显示文件数与总大小
- 设置页重排：按功能分组并加图标，顶部应用信息卡显示当前版本（v1.1.1）
- 目标应用选择改为带应用图标的列表（图标内存缓存）+ 已选标记 + 圆角搜索框
- 建立统一设计体系：`Theme.kt` 里集中定义 Typography / Shapes / 工具强调色渐变；深色配色重新调校，
  状态栏与导航栏图标跟随主题（运行中切换深色模式也即时刷新）
- 空状态、倒计时弹窗、删除确认等细节重新设计
- 构建产物文件名带版本号：`android-tools-1.1.1-debug.apk` / `android-tools-1.1.1-release-unsigned.apk`
  （版本号取自 `defaultConfig.versionName`，自动跟随）
- versionName `1.1.1` / versionCode 5

`1.1.0` — 对应需求 `需求/version-1.1.0.MD`：

- 应用图标换成需求提供的「录屏」图标（红色圆环 + 圆心，自适应图标）
- 新增**悬浮控制按钮**：录制开始时浮在其他应用之上，点击暂停/继续、长按停止、可拖动；
  红色（暂停条）= 录制中，灰色（播放三角）= 已暂停；设置里可开关，未授权时引导到系统授权页
- 工程名改为 `android-tools`（Gradle 根项目、本地目录、Git 仓库）
- versionName `1.1.0` / versionCode 4

`1.0.1` — 画质修复，对应需求 `需求/version-1.0.1.MD`：

- **修复码率单位错误**：`KEY_BIT_RATE` 需要 bit/s，之前把 Kbps 直接传入（22118 而非 22118000），
  编码器按最低档编码，导致无论怎么调码率都很糊 —— 这是本次"很糊"的根因
- 采集分辨率改为物理全屏尺寸（含状态栏/导航栏），不再被窗口尺寸和 16 像素对齐缩小
- 按真实编码器能力选择分辨率，保持镜像 1:1，不产生隐式缩放
- H.264 优先使用 High profile（同码率下画质更好）
- 配合 1.0.0 的固定帧率（CFR），避免高刷新率屏幕被录成 60~120fps 而导致单帧码率过低

`1.0.0` — 首个可用版本，覆盖需求文档 `需求/version-1.0.0.MD`：
整屏/单应用录制、Gradle 构建、帧率/画质/保存位置等常用配置。

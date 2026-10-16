# HighSpeedIEM（高速耳返）v5.7

单一输入设备 → 单一输出设备的低延迟音频转发应用。Kotlin / Jetpack Compose /
Material Design 3 前端，C++ 直接调用 AAudio；没有 Oboe、重采样或格式转换库。
最低 API 27、目标 API 37，四 ABI 和现有主题、导航、权限、MMAP 策略保持不变。

## 会话与生命周期

`audio/RelaySession.kt` 是 Activity 与前台服务共享的唯一内存会话；主线程管理
设备、权威参数、混音和 Compose 状态。参数对话框直接订阅会话，不保存会覆盖
联动结果的独立参数副本。准备、启动、停止期间保持 busy，界面阻止冲突操作。
`ForwardingService` 继续负责 microphone/mediaPlayback 前台服务及部分唤醒锁。
设备热插拔、运行中路由更新、通知入口、任务清除时停止均保留。

启动由同一个生命周期协程串行执行：

1. 前端校验并解析采样率，构造不可变 24 字段配置和混音快照。
2. `nativePrepare(config, masks)` 创建通知通道，先开输出、后开输入，验证实际
   几何和 PCM，设置缓冲并分配 scratch；不启动任何流。
3. IO 线程读取本次实际输出 FramesPerBurst 和输入 BufferSizeInFrames。
4. 主线程归一化阈值并更新权威参数，传递最终 `[读取倍数, 附加帧, 积压阈值,
   DRAIN稳定阈值, DRAIN强制次数, DISCARD稳定阈值, DISCARD强制次数]`。
5. `nativeStartPrepared(thresholds)` 只校验和采用前端最终值；初始化状态机，
   先启动输入并确认 STARTED，再启动输出并确认 STARTED。

`ConfigField` 在 Kotlin `RelayConfig.kt` 和 `jni_bridge.cpp` 中定义相同字段顺序：
输入 9 项、输出 10 项、阈值 3 项、MMAP 2 项。旧 nativeStart、25 字段配置和
输出自动缓冲开关已删除。JNI 异常转换为前端可见错误。

开流/启动期间收到设备移除、停止或原生错误，生命周期任务完成当前阻塞操作后
立即释放，不能重新报告成功。准备成功但未启动也可停止。正常停止保持幂等：
解除回调工作许可 → 停止并关闭输出、等待回调结束 → 停止并关闭输入 → 退出并
join 通知线程、关闭 eventfd → 释放 scratch。通知线程调用 Listener 只投递主线程
消息；主线程再调度 IO 停止，不在通知线程内停止或等待自己。

## 四状态回调

`RelayEngine::get()` 单例持有唯一的 `RelayCallbackMachine`。阶段、连续稳定次数、
上次积压、XRun 基准及门槛停滞计数仅由输出数据回调修改；外部线程通过原子首错
通道和持续 stopRequested 请求退出。状态切换不使用协程，也不等待前端。

| 状态 | 一次回调行为 | 退出条件 |
| --- | --- | --- |
| DRAIN | 一次零超时读取实际输入 capacity，丢弃并静音 | 连续稳定次数达到所选阈值（默认 4），或达到所选强制次数（默认 200）；稳定＝短读且有效积压 ≤ max(2×回调帧, 2×输出 burst) |
| DISCARD | 一次零超时读取 numFrames，丢弃并静音 | 连续稳定次数达到所选阈值（默认 5），或达到所选强制次数（默认 200）；稳定＝短读或有效积压变化 ≤ numFrames |
| RUNNING | XRun → 积压阈值 → 读取门槛 → 一次 numFrames 读取和混音 | 积压超阈值回 DRAIN；致命错误进入 EXIT |
| EXIT | 不读、不混音，输出范围安全时 memset 静音并返回 STOP | 会话内不可返回运行态，后台完成停止 |

初始化 DRAIN 不通知。进入 DISCARD 通知“正在同步”；进入 RUNNING 建立输入/
输出 XRun 基准并通知“开始正常转发”；运行中回 DRAIN 通知“清空积压缓冲”。
**切换后的下一次回调才处理新阶段音频**，不在同次回调贯穿多个阶段。
允许无限多轮 RUNNING → DRAIN → DISCARD → RUNNING，没有恢复轮数、累计时间、
冷却、恢复一半、共享丢弃预算或无进展自动退出策略。

RUNNING 每次先更新两端有效 XRun 基准。负查询使该方向基准失效；恢复有效只建立
基准；计数下降按重建处理。输入增加时只读一次输入 capacity 丢弃；输出单独增加
不读输入；两端增加只执行一次容量读取且保留两端增量。本次均 memset 静音并保持
RUNNING。XRun 不改变输出大小；计数恒 0 不证明没有输入溢出。

积压估算为 FramesWritten − FramesRead，不是保证可读帧数。未知时跳过积压判断。
开启 B 且估算严格大于 B 时本次不读，静音转 DRAIN。门槛只作用于 RUNNING：
`F_raw = 倍数 × 实际输出 burst + 附加帧`，输入实际大小有效时再取较小值。
不足时不读、静音；连续约 1 秒后放行一次非阻塞读取并发事件，未知计数也放行。
通过门槛仍只请求 numFrames。短读只混音 got 帧、尾部补零，不补读。

混音保留 I16、Float、I24 packed、I32，每输出声道按输入掩码逐样本平均；未路由
声道为 0。32 位原子路由、固定数组 Plan、调试 NonSilentOutputSeen 保留。
`StartupFramesDiscarded` 只统计初始 DRAIN/DISCARD；后续恢复与输入 XRun 清理计入
`RuntimeFramesDiscarded`。实时参数安全读取原子 `CallbackState`。

## 内存与通知

scratch 在准备时一次分配：
`max(实际输入capacity, 实际输出capacity) × max(两端声道数) × max(两端样本字节数)`。
验证正 capacity、1..32 声道、受支持 PCM，并逐项 checked size_t 乘法。
每回调验证输入/输出所需字节，每次容量读取再检查容量所需字节；越界立即错误退出，
不分片、不扩容、不截断。非法帧数、空输出或不可安全计算的输出长度不执行内存写入。

音频回调不分配/释放堆、不构造字符串、不格式化日志、不加锁、不调 JNI、不关流。
`RelayEventDispatcher` 使用 eventfd 和准备时创建的等待线程；普通事件为固定 SPSC
队列（只有数据回调生产），拥塞时用预分配原子快照槽保留最新状态和两端 XRun 合计。
所有共享原子使用四 ABI 可无锁的 32 位值/指针；64 位事件值只在队列发布边界内传递，
溢出槽用无锁 32 位原子拆分表示。

数据回调、AAudio 错误回调及控制侧致命错误使用独立首次写入邮箱，首错不依赖队列
容量且持续请求退出。通知线程生成含原始 AAudio 错误码文字、状态、请求帧及字节数
的错误并调用 `onNativeError(sessionId, text)`；普通事件调用
`onNativeEvent(sessionId, sequence, type, value1, value2)`。两方法与 Listener 均 @Keep。
不再有 500ms 恢复计数轮询；实时参数面板每秒刷新仍只是可见页遥测。

Kotlin 验证会话编号，忽略迟到旧会话消息。普通提示只有最新一项，错误优先；收到
错误立即停止，不等待 Snackbar。`ErrorNotice` 按唯一 ID 保留，后台、旋转和重建不
消费未显示完的错误。重复错误不会被普通状态覆盖。

## 采样率

`SampleRateMode` 区分 AUTO、EXPLICIT、SYNC_OUTPUT；所有输入默认 SYNC_OUTPUT，
未受同步约束的新输出默认 AUTO/0。同步只在 Kotlin 启动快照解析，不向原生传哨兵。
输入 AUTO 传 0，让 AAudio 独立选择；输入自动格式仍跟随输出实际格式。
开流后两端实际采样率或格式不同即关流报错，不重采样、不重试或静默同步。

原始能力先过滤正数、去重、排序；下表只依据原始数组，不递归使用填充结果：

| 输入原始能力 | 输出原始能力 | 输入选项 | 输出选项 |
| --- | --- | --- | --- |
| 有 | 有 | 同步、0、输入 Hz | 输出 Hz；选中输入非同步时可含 0 |
| 空 | 有 | 仅同步 | 输出 Hz，不含 0 |
| 有 | 空 | 同步、0、输入 Hz | 复制当前选中输入原始 Hz，不含 0 |
| 空 | 空 | 仅同步 | 仅 48000 Hz |
| 未选输入 | 空 | 按各输入自身能力 | 空列表，显示未解析并拒绝启动 |

仅当前选中输入影响输出列表。同步时移除所有输出的 0，无效选择改最低有效 Hz；
有效明确 Hz 保留。解除同步恢复允许的 0 选项，但不强制改回 0。修改未选输入不影响
输出。设备新增、移除、重新枚举和切换选中都会重新应用规则。

## 阈值与缓冲

`RelayParamRules` 是纯计算规则。倍数保留 0..10、附加帧保留 0 和 1..1024 二次幂、
积压阈值保留 0、100..1000 百帧及 2000..10000 千帧选项。B=0 关闭积压；读取两项
均 0 关闭门槛。B>0 时必须严格大于 F_effective。

- 改门槛或几何：保留门槛，B 调到最小有效正数；无项则 B=0。
- 改正数 B：保留 B，先保留倍数、选择最接近的有效附加帧，必要时降低倍数到 0。
- burst 未知且倍数>0：改门槛就关闭 B；改正数 B 就把倍数降到 0 后选择附加帧。
- 开流后用本次真实 burst 和输入大小最终归一化，原生只校验、不隐藏修复。

调整原因和最终选中值同步反映在前端；不伪造 192/256 burst，也不使用不匹配的旧缓存。
输出大小默认 **4 burst**，所有性能模式均准备时设置一次，裁剪到实际容量，以 setter
实际返回值为准；用户选 0 时不调用 setter。输入默认 0，非零仍按平台尽力设置，失败
记录后继续。不再有输出自动初始 2 burst 或 XRun 自适应增大。

MMAP 开启仍表示保留平台原策略（包括 UNSPECIFIED），关闭临时 NEVER；分别开流后
恢复进程原值。没有强制 AUTO/ALWAYS 或修改旧平台服务策略。设备类型中文/原始枚举
映射见 `设备类型映射.md`。

## 构建与验证

AGP 9.4.1、Kotlin Compose 插件 2.3.21、NDK 30.0.16248370、CMake 4.1.2，未升级。
Windows 构建输出保持 `C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/`，临时产物不进源码。

```text
gradlew.bat --project-cache-dir C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/project-cache :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintRelease
adb -s emulator-5554 shell am instrument -w -r cn.xiaotao29.HighSpeedIEM.test/androidx.test.runner.AndroidJUnitRunner
```

`RelayParamRulesTest` 遍历所有有限阈值组合、未知/实际 burst 与输入大小，检查采样率
空能力矩阵和 JNI 模式解析。`CallbackMachineTest` 注入生产状态机的 IO，覆盖阶段
稳定/复位/次数上限、单次读取、XRun、1000 轮恢复、门槛停滞、四 PCM 混音、内存边界、
拥塞首错及通知线程反复销毁。注入入口及故障开关仅编入 debug。

`PreparedLifecycleTest` 验证准备后未启动停止、失败清理、分配失败、无数据回调首错、
旧会话隔离及错误自动停止。`SampleRateSessionTest` 验证真实会话选中输入联动和参数
即时更新。原 `RelayProgressTest` / `RelayParamsTest` / `LatencyBufferTest` /
`MMapPolicyTest` 迁移到准备/启动两步，保留 NEVER/UNSPECIFIED 恢复、共享/独占请求、
I16/Float、连续推进及混音更新；`ErrorReportingTest` 和 `MixerDialogTest` 保留旋转、
重建、后台、遥控/键盘与长列表覆盖。

### v5.7 DRAIN / DISCARD 退出参数

转发参数设置新增四个共享 Material 3 下拉菜单，默认 DRAIN 4 / 200、DISCARD
5 / 200。四个菜单统一为 1..9、10..100（步长 5）、200..2000（步长 100），
不增加 150。每个阶段不稳定均复位连续计数，强制次数按
该阶段累计回调次数计算。参数保存在现有内存会话中，切换设备、旋转和重建
Activity 后保留，下次启动时采用，并用于启动清理及每一轮积压恢复。

允许先选出不一致的组合，启动前明确拒绝“强制退出次数小于稳定阈值”，不自动
调整或覆盖用户选择。前端预检及原生启动均校验范围与大小关系；不一致时不启动
流。状态机仅在启动前接收不可变参数，回调内继续保持无分配、无锁。

`PhaseParamsTest` 验证手机横竖屏下四个下拉菜单的真实选择、重建后参数保留、
启动错误原文显示、原生启动前拒绝非法组合和非默认参数的实际流推进。
`CallbackMachineTest` 补充 1 / 2 / 10 / 100 / 2000 次边界、两阶段不稳定复位、
恢复沿用所选参数及下一次会话默认值复位；`RelayParamRulesTest` 遍历两个阶段
的所有稳定阈值 / 强制次数组合，验证关系校验与启动参数顺序。

本次验证（2026-10-17）：四 ABI Debug / Release、测试 APK、6 项 JVM 测试及
完整 lintRelease 构建通过（0 错误，9 项既有警告）；API 37 / x86_64 手机模拟器
全套 38 项仪器测试通过，含 10,556 条原生状态机行为断言，crash buffer 为空。
Release 为 v5.7 / versionCode 62，包含四 ABI，v2 签名验证通过；产物为
`C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/app/outputs/apk/release/cn.xiaotao29.HighSpeedIEM_5.7.apk`。

### v5.6 设备卡片菜单键操作

键盘/遥控器只聚焦整个设备卡片，卡片内三个按钮通过 `focusProperties.canFocus`
排除在键盘焦点遍历之外，触摸点击与无障碍动作仍保留。聚焦卡片后，原生
`KEYCODE_MENU` 按松开一次依次打开设备能力 → 测试设备 → 设备参数 → 设备能力；
无需先选中设备。返回、Escape、点击外部或关闭按钮会结束当前循环，下一次菜单键
从设备能力重新开始；遥控操作关闭后焦点回到原卡片。

设备动作状态由导航布局之外唯一的 `DeviceMenuState` 管理，`rememberSaveable` /
`listSaver` 保留设备、当前面板及遥控来源。三种面板互斥；原生 TooltipBox 禁用自动
悬停/焦点触发，弹出后将焦点交给内容区域。能力表使用原生 ScrollState，方向键每次
滚动一行，PageUp/PageDown、触摸和无障碍滚动仍由 Compose 处理。窗口高度及定位
使用 `LocalWindowInfo` 和 Material 3 当前原生定位器，横屏内容过长时在控件内部滚动。
滚动状态置于 TooltipBox 内容作用域之外，原生弹窗重新定位时不会重置到顶部。

参数 Dialog 由根界面承载，横竖屏布局切换及 Activity 重建后保留当前面板，遥控打开时
聚焦首个参数。Dialog 内部下拉菜单同样处理菜单键，避免焦点进入独立弹窗后快捷键失效。
菜单键只在完整松开时切换，长按重复的按下事件不会连续跳过多个面板。开流、关闭或
转发期间继续遵守原有操作锁，设备移除会关闭对应面板。

开发依据：[Compose 焦点控制](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior)、
[按键事件传播](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/key/onPreviewKeyEvent)、
[界面状态保存](https://developer.android.com/develop/ui/compose/state-saving)。

本次验证（2026-10-17）：四 ABI Debug / Release、测试 APK、5 项 JVM 测试及
`lintRelease` 构建通过（0 errors / 9 warnings）。API37 / x86_64 手机模拟器完整
35 项仪器测试通过。新增 `DeviceMenuNavigationTest` 覆盖输入/输出卡片、未选中/
已选中、Tab/方向键跳过三个按钮、菜单键循环、各阶段返回重置、Escape 关闭、
触摸/无障碍入口、连续滚动至能力表末行并返回顶部、参数界面旋转保留及下拉菜单
中的菜单键。Release 另实看浅色竖屏及深色横屏，确认控件焦点、循环、末行滚动和
关闭返回原卡片正常。最终 Debug / Release crash buffer 均为空。

最终安装 5.6 / versionCode 61 的非 debuggable 签名 Release，手机模拟器保留运行，
恢复浅色竖屏、未选设备的默认输入页。证据在
`C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/qa56/`，包括 `build.txt`、`tests.txt`、
`release-portrait-capabilities.png`、`release-portrait-test.png`、
`release-portrait-params.png`、`release-landscape-dark-scrolled-bottom.png`、
`release-landscape-dark-params.png`、`release-final-home.png`。
四 ABI APK：
`C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/app/outputs/apk/release/cn.xiaotao29.HighSpeedIEM_5.6.apk`；
v2 签名验证通过，SHA-256：
`7B0DDFEEA3932C8B12E039CBD14BD2B76D4138457AF0DCB3E767878C0D1CC7BA`。

### v5.5 设备参数与混音焦点

输入、输出设备的 `StreamParams.exclusive` 均默认 `true`，界面标签统一为
`Exclusive`；用户仍可取消勾选。该值是 AAudio 开流请求，实际共享模式仍由平台决定。

混音输入对话框使用 Material 3 原生 `ListItem(checked, onCheckedChange)`，由组件
提供焦点高亮、选中态、方向键/键盘操作和 Checkbox 无障碍语义，不再给展示型列表项
额外挂载 `toggleable`。复选框本身仍不单独响应点击，整行只有一个操作目标。
草稿、取消/返回丢弃、确定提交、旋转保留及 busy 锁保持原有逻辑。

开发依据：[Android Compose 焦点指示](https://developer.android.com/develop/ui/compose/touch-input/focus/focused-state)，
以及工程当前 Material 3 1.5.0-beta01 的多选列表项实现，无需升级依赖。

本次验证（2026-10-17）：四 ABI Debug / Release、测试 APK、5 项 JVM 测试和
`lintRelease` 通过（0 errors / 9 warnings）。API37 / x86_64 手机模拟器全套
32 项仪器测试通过，混音测试增加方向键上下移动、确定键勾选及 Tab 导航断言。
实看浅色竖屏、深色横屏的混音焦点与勾选状态，以及输入/输出 Exclusive 默认值；
取消勾选、重新勾选正常。Debug 和非 debuggable Release 均完成前端选择设备、混音
配置、默认 Exclusive 请求启动转发并停止。两版 crash buffer 均为空。

最终安装 5.5 / versionCode 60 的签名 Release，恢复浅色竖屏、未选设备的默认输入页。
日志、界面 XML 和截图在 `C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/qa55/`，
包含 `build.txt`、`tests.txt`、`debug-mixer-landscape-dark-focused.png`、
`debug-mixer-portrait-light.png`、`release-mixer-focused.png`、`release-final-home.png`。
四 ABI APK 为
`C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/app/outputs/apk/release/cn.xiaotao29.HighSpeedIEM_5.5.apk`，
v2 签名验证通过，SHA-256：
`C05B7B942CC77EB72E413A59D2BBA0E32A9DB03923ADB828807565912E43C0FF`。

### v5.4：2026-10-16 至 2026-10-17 验收结果

- 四 ABI debug / release、测试 APK、JVM 测试与完整 lintRelease 构建通过。
- 5 项 JVM 测试通过，包含 84,480 组有限选项/几何/修改来源的归一化检查。
- 手机模拟器 API37 / x86_64 全套 **32 项仪器测试通过（0 失败、0 忽略）**，
  本轮使用 `-e inputChannels 2`，覆盖双声道 I16 / Float、共享/独占请求及反复启停。
  `CallbackMachineTest` 内含 **2098 项生产状态机行为断言**。
- lintRelease：**0 errors / 9 warnings**（既有权限、资源/图标、依赖、备份和唤醒锁等提示）。
- release 的两种 JNI Listener 原名/签名在 R8 mapping 中保留；四个 librelay.so 均不含
  debug 故障注入 JNI。release v2 签名验证通过，沿用工程现有签名配置。
- release 实际走前端选择 → 混音设置 → 前台服务启动 → 收到“开始正常转发” → 停止。
  本模拟器扬声器原始采样率为空，复制选中输入能力后默认最低 8000 Hz；输入同步
  输出，实际两端 2ch / 8000Hz / Float，输出大小请求 640、系统返回 640，读取持续推进。
  仪器测试另覆盖 48000 Hz 和 44100 Hz；没有把这些选择暗中改回设备默认值。
- release 浅色竖屏、深色横屏转发，以及输入“同步输出采样率”和输出默认 4 burst
  参数界面已检查；旋转时音频保持运行。最后恢复浅色竖屏、未选设备的默认输入页，
  安装版本 5.4 / versionCode 59，非 debuggable，topResumedActivity 为 MainActivity。
  crash buffer 为空，手机模拟器保持运行。

本地证据保存在 `C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/`：
`delivery-build.txt`、`delivery-tests.txt`、`app/test-results/testDebugUnitTest/`、
`app/reports/lint-results-release.*`、`qa54/release-portrait-running.png`、
`qa54/release-landscape-running-dark.png`、`qa54/release-input-sync-dark.png`、
`qa54/release-landscape-buffer-dark.png` 和 `qa54/release-final-home.png`。

交付 APK：`D:/xuyuntao/Desktop/cn.xiaotao29.HighSpeedIEM_5.4.apk`，
包含 armeabi-v7a / arm64-v8a / x86 / x86_64；与构建目录原件 SHA-256 一致：
`7B96C7A2F05B0AEC40292B37AB3DC4B952EFFE505BB9D276F504A3CA01F16767`。

测试仅在手机模拟器进行；独占请求可回退，
不声称得到实体设备独占 MMAP，也不证明输入 XRun 恒零、无爆音、真实听感或端到端延迟。
实体手机/电视 API28、USB 热插拔、不同设备默认采样率及硬件回环尚待设备侧验证。

开发依据：[Android AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)
中的非阻塞读取、回调约束、状态等待与关流同步；现有界面继续沿用 Material 3 原生
组件、窗口安全区域、深色主题及无障碍/键盘语义。

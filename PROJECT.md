# HighSpeedIEM

多通道实时音频转发：把麦克风收录的声音低延迟地转发到扬声器/耳机/其他输出设备。
包名 `cn.xiaotao29.HighSpeedIEM`，版本 `2.2`（versionCode 13），显示名见 `app/src/main/res/values/strings.xml`。

## 构建与测试

前置：Android SDK（`local.properties` 中的 `sdk.dir`）、NDK 30.0.16248370、CMake 4.1.2、JDK 17+（`JAVA_HOME` 指向 Android Studio 自带 jbr 即可）。

构建输出全部重定向到 `C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM`，项目目录只保留源码：

```
./gradlew :app:assembleDebug -Pabi=x86_64     # 快速开发/测试构建（仅 x86_64）
./gradlew :app:assembleDebug                  # 默认四个 ABI：armeabi-v7a,arm64-v8a,x86,x86_64
./gradlew :app:assembleRelease                # 交付构建（签名沿用 debug 证书）
./gradlew :app:testDebugUnitTest              # JVM 单元测试（转发表规则等）
./gradlew --project-cache-dir C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/project-cache <任务>   # 避免项目内生成 .gradle
# Kotlin 插件会重建空的 .kotlin/ 目录，可随时删除，不影响构建。
```

Phone 平台统一测试（instrumentation，单一入口，场景可筛选）：

```
adb install -r C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/app/outputs/apk/debug/app-debug.apk
adb shell pm grant cn.xiaotao29.HighSpeedIEM android.permission.RECORD_AUDIO
adb shell pm grant cn.xiaotao29.HighSpeedIEM android.permission.POST_NOTIFICATIONS
adb shell am start -n cn.xiaotao29.HighSpeedIEM/.MainActivity
adb shell am instrument -w -e scenarios native-selftest,launch-tabs,device-validity,selection-preview,spectrum-tooltip,ringtone-test,route-rules,task-route-editing,forward-start-stop,error-stop,rotation \
    cn.xiaotao29.HighSpeedIEM/.test.UnifiedTestInstrumentation
```

场景：`native-selftest, launch-tabs, device-validity, selection-preview, spectrum-tooltip, ringtone-test, ringtone-native-drain,
route-rules, task-route-editing, task-toolbar, forward-start-stop, error-stop, rotation, compact-mode, compact-routes, compact-binding-phone`。
输出逐场景 `=== SCENARIO <名称>: PASS/FAIL - 说明` 与 `=== SUMMARY`。
默认只在正常分辨率的手机模拟器执行，不使用 `all`（会包含 `compact-mode`）。
低分辨率、电视、手表等平台仅在用户显式要求时测试。

## 架构

- `app/src/main/cpp/engine.cpp`：C++17 + Oboe 1.11.0（源码在 `cpp/oboe/`，已锁定版本）。
  每台设备只开一条多声道流，转发对通过无锁环形队列传递单声道采样；
  回调内无分配、无锁、无 JNI。输入输出由系统协商，应用层不做重采样：
  两端实际采样率不一致时关闭全部流并返回 `ErrorInvalidRate`。输出缓冲从
  2 个 burst 起步并用 `LatencyTuner` 调整；积压时丢弃过期音频而不是累积延迟。
- `audio/MonitorSession.kt`：会话状态机（设备枚举、探测、参数、转发表、后台错误）。
  只存在于内存，不持久化；Activity 重建时由 `MonitorApplication` 持有并复用。
- `audio/DeviceValidator.kt`：实际开流探测。输入录制约 1.2 秒逐通道查峰值，
  输出只验证能否打开；最多 4 个设备并行，全部释放后串行复核失败/静音设备。
  探测通过的通道默认启用，失败或静音默认取消勾选但保留在列表。
  结果按设备身份缓存，普通刷新保留用户手动勾选。
- `audio/RingtoneTest.kt`：系统默认铃声 → MediaExtractor/MediaCodec → AudioTrack
  写入所选输出通道；独占测试走 AAudio 原生写路径。
- `service/ForwardingService.kt`：microphone + mediaPlayback 前台服务 + CPU 部分唤醒锁。
  从最近任务清除应用先停止转发并释放资源；进程被系统杀死后不自动重启。
- `ui/`：Jetpack Compose + Material 3。固定顶部 Small app bar 与底部三等分
  Navigation bar；通过导航切页，不支持滑动切页。

## 功能逻辑要点

**选中与启用相互独立**：输入、输出列表各自最多选中一个通道（再点取消），
选中用于频谱或铃声测试；启用复选框可多选，决定通道是否出现在任务页下拉框。
卡片点击后行获得焦点；选中或持焦点的行显示主色边框。

**输入页**：通道卡片（设备名、通道索引、启用复选框；能力详情、独占复选框；
采样率/采样深度/录音预设）。页面不再保留常驻频谱框；选中后按该通道参数开流，
在对应通道卡片附近通过原生 Material 3 Rich tooltip 显示实时频谱。
开流期间可先显示“等待音频数据…”，报错立即取消选中、关闭频谱并释放资源。
弹层保持显示直到关闭；点击外部、返回键、弹层/卡片释放、切页或 Activity 离开前台时，
同步取消选中并安排释放预览流（包括正在打开的流），正常关闭保留通道启用状态。
返回页面不恢复预览；旧弹层的清理不会关闭后来重新选中的同一通道。
关闭时保留弹层尺寸与最后一帧画面，由原生 TooltipBox 在原位置缩小淡出，
与载入动画方向相反；退出动画结束后移除内容。采集、会话频谱数据及通道资源在关闭时立即清理，
不等待视觉动画结束。
手动测试出错时清除该通道选中与启用、删除引用它的转发行，
同设备其他声道不受影响。

**输出页**：同样的卡片；选中通道即完整播放系统默认电话铃声，无额外测试按钮或总时长限制。
播放结束释放全部资源并取消选中，保留启用；再次点击选中通道立即取消播放和选中、释放资源。
点击另一通道时取消上一段铃声，等待其资源释放后自动播放新通道；整个列表至多选中一个通道。
播放与清理期间通道卡片保持可选，连续切换只播放最后选中的通道；参数与启用复选框在音频占用期间仍锁定。
默认铃声为空、不可读取或无音轨时显示提示，并按自然结束处理；离开本页或 Activity 离开前台时取消播放和选中，
保留启用，返回页面不恢复铃声。手动测试报错清除失败通道的选中与启用。
提示条靠近底部导航栏，输出列表无需为角落按钮保留空间。
铃声按解码缓冲逐段写入所选通道；解码结束后等待 AudioTrack 播放位置或 AAudio 设备缓冲实际耗尽，
再释放输出、解码器和提取器。解码/写入/尾部排空仅检查连续无进度超时，正常长铃声不触发会话超时。
`ringtone-test` 使用 24.25 秒默认铃声验证点击自动播放、自然结束取消选中、再次点击取消、单选切换、连续切换、
待播放选择取消、切页与后台资源释放、缺失资源提示及再次播放；
临时铃声和默认铃声设置在测试后清理、还原。`ringtone-native-drain` 验证 AAudio 尾部缓冲释放。

**任务页**：两列转发下拉框，轮廓上直接标“输入通道/输出通道”，只列出启用的通道
并保留空白项；菜单项可折行、行距宽松、当前选中项高亮带勾。
空白插入行始终保留；同一配对自动去重；只有完整配对参与启动。
转发表使用 Kotlin 不可变行与列表，通过 Compose `mutableStateOf` 一次性发布整表；固定行 ID 同时用于编辑定位和 LazyColumn 的 key。
新增、清空、去重与禁用/消失通道的剪枝统一由 RouteModel 维护，外部只能读取列表与字段，旧界面快照不会被原地改写。
所有选择入口共用运行/准备/清理锁与启用通道检查；已经删除的行或失效菜单回调不会重新插入转发。
`task-route-editing` 在正常手机直接操作下拉菜单，验证连续增删、单侧保留、前行删除、去重、展开菜单剪枝及实际启停；不通过切 tab 强制重建掩盖刷新问题。
底部中央的 Material 3 横向悬浮工具栏包裹 Split 按钮：主键“启动 N 个转发”（N=0 时禁用），副键打开系统后台运行保护设置。
工具栏在任务页列表上方独立悬浮，尺寸按键组内容确定，与底部 Navigation bar 保持 12dp 间距，外侧露出列表。
底层只有一个 LazyColumn，标题、状态和转发行均为列表项；列表底部留白按工具栏实测高度与间距计算，末行可完整滑到工具栏上方并操作。提示条也根据实测高度避让工具栏。
准备、运行和停止清理期间列表禁止编辑。
每行显示已打开流的实际参数（采样率、编码、声道、共享模式、burst、xrun 等），约每 3 秒更新。

**参数**：采样率、采样深度、录音预设默认自动由系统协商；同一设备多声道共享一组设置，
只有通道 0 可改。独占默认关闭并需实际开流探测支持，不接受静默降级为共享。

**错误原则**：转发运行中发生致命错误时先停止全部转发、等待资源安全释放，
再显示原始错误信息（不重写系统的错误文本）。

## 低分辨率与手表模式

屏幕任一物理维度的像素 ≤600，或设备为手表（UI_MODE_TYPE_WATCH）时，统一显示 Material 3 三页界面：

- 第 1、2 页分别是任务表首行输入、输出下拉菜单的完整选项列表，包括空白项及全部启用通道。
  列表使用 Material 3 ListItem；显示设备 ID、通道编号、设备类型，选中项使用 primaryContainer 高亮。
  页面没有标题、说明或额外操作控件；点击选中项保持选择，空白项仅清除当前页对应的一侧。
- 第 3 页信息模块占用中号按钮上方的全部剩余空间：顶部复用手机任务页的状态提示框，下面显示映射转发对的输入与输出实际运行参数，
  内容可独立滚动，未运行时参数区域保持空白。底部居中放置一个 Material 3 中号填充启动/停止按钮，默认高度 56dp。
  启动文字为“启动 N 个转发”，N 是整张任务表的有效配对数；启动、停止与手机共用同一会话与前台服务。
- 底部的 `CompactPageIndicator` 独立占用 32dp，包含三个圆点，当前页高亮；页面左右滑动切换。
- 前两页固定映射启动时手机任务表第 1 行的空白添加行，以固定转发行 ID 绑定，不随列表索引变化。
  该行选中输入或输出后，后台继续保留新增空白行，允许添加任意其他转发对；两页仍编辑原绑定行。
  清空一侧保留另一侧及映射；两侧都清空后删除旧行并映射尾部空白新增行，不接管其他已有转发对。
  冲突去重、通道禁用或设备消失导致绑定行被删除时，同样映射空白新增行；后续选择创建新转发对。
  手机任务下拉框与这两页共享同一转发行，Activity 重建及模式切换保留会话内映射；进程重启从新的启动空白行开始。
  运行、启动、加载及资源清理期间禁止编辑选择。
- 进入本模式时清理手机端设备预览/测试。圆屏按窗口尺寸添加内容安全边距，状态与文本避开圆形边缘；
  其他异形屏沿用 Scaffold 的系统安全区，较大字体及长参数允许换行和滚动。

其余设备保留完整三页界面；界面接受系统自动旋转。`compact-mode` 可验证手表或手机低分辨率切换；
`compact-routes` 在当前极简界面验证实际列表选择、多配对启动/停止、映射行参数、操作锁定、单侧保留、双侧清空、冲突去重与通道禁用后的重绑定。
`compact-binding-phone` 在正常分辨率手机中临时加载实际 CompactFlow 组件并执行上述场景，结束后恢复手机 UI；不更改分辨率或设备类型。

## 加载提示

冷启动探测输入输出设备期间显示居中的加载圈；标准三页界面下同时在圈下方显示
“正在加载输入输出通道…”提示，极简模式只显示加载圈。加载期间提示层吸收触摸，
测试与转发操作在探测资源释放后才开放。

## 视觉设计（2026-10-09）

- 标准三页使用统一的标题、说明、状态标签；浅色/深色均采用主题的 surface container 层次，
  主操作使用 primary/onPrimary，辅助操作使用 secondaryContainer/onSecondaryContainer。
- 设备卡与转发卡统一 24dp 圆角、16dp 内边距、12dp 卡间距。设备卡增加输入/输出图标和
  加粗的“ID 设备编号 · 通道 索引”标题与常规小字“设备类型 · 设备名”副标题；“设备能力”通过原生 Rich tooltip 显示完整能力信息，每行一个参数，
  左列参数名加粗、右列参数值常规字重。提示按文字自适应大小，超宽自动换行、超高可滚动，
  点击外部或返回键关闭。输入参数为两列加整行录音预设。
- 设备类型统一使用中文标签，覆盖 Android API 37 的 35 个公开定义及 AOSP 的回声消除参考类型；
  未定义类型回退为系统常量名加编号，能力提示始终保留原始类型。完整列表见 [设备类型映射](设备类型映射.md)。
- 所有下拉框保留原生 OutlinedTextField；菜单宽度 240–360dp，选中项背景高亮、长文本折行。
  收起时的已选值超过可用宽度会使用原生文字滚动接口自动横向滚动，短文字不滚动；
  首尾各停留 1.5 秒，展开菜单时暂停，值或宽度变化后从开头重新显示。边框、标签和箭头保持固定。
  普通项底色与选中高亮共用同一圆角矩形形状。
  任务页收起时显示“ID 设备编号 · 通道 索引”，仅设备编号与通道索引的数值通过原生
  OutputTransformation 局部加粗；展开菜单第一行显示“ID 设备编号 · 通道 索引”，
  第二行以常规小字显示“设备类型 · 设备名”。空白项仍用于清除选择。
- 可操作的参数框采用 outline 轮廓色，在深色选中卡片上保持可辨识边界；运行参数自动折行完整显示。
  系统字体放大超过 120% 时，转发卡的两列改为上下排列，保留设备/通道身份的可读性。
- 实时频谱为线性频率轴的柱状图，0 至 -90 dBFS；相邻 FFT bin 按峰值合并为最多 48 根柱，
  随可用宽度调整数量，保留窄峰和柱间间隔。频谱使用通道卡片作为 Rich tooltip 锚点，
  标题标明设备 ID 和通道索引；弹层宽度不超过 360dp 并适应窗口，图表内容高度为 144dp。
  原生控件自动选择锚点上方或下方，位置限制在系统栏及安全区域内。
- 1.8 起标准任务页转发按键组统一使用 Material 3 原生填充分裂按钮
  LeadingButton / TrailingButton，默认小号最小高度、圆角、内边距、labelLarge 字体、
  20dp 图标和至少 48dp 触摸区域。两键共用原生填充按钮的阴影状态与禁用配色；
  常态、按下、聚焦和禁用为 0dp，悬停为 1dp，不再强制全状态 6dp 阴影。
  仅可用态的颜色角色不同：保护设置为 secondaryContainer / onSecondaryContainer，
  启动与停止转发为 primary / onPrimary。原生最小高度允许较大字体自然增高。
  极简模式转发键沿用既有 ActionStyle 样式；1.6 起使用 Material 3 中号高度、
  内边距、图标和字体，小宽度时隐藏图标，较大字体允许按钮自然增高。
- 1.9 起标准任务页使用原生 `HorizontalFloatingToolbar(expanded = true)` 包裹键组，
  默认容器形状、配色、内边距及最小高度；不设置滚动收起行为，始终固定于 Navigation bar 上方。
  容器在页面底部居中、宽度随内容确定，按钮自身保留 1.8 的原生样式。
  列表与 Snackbar 使用容器实际测量高度避让，适配字体增大和启动/停止文字变化。
- 2.0 起任务页工具栏容器使用固定 6dp 阴影，展开与收起均指定同一 elevation；
  禁用、可启动、运行、按下与聚焦等状态都使用相同容器阴影。
- 保留全局无波纹、反选清边框、下拉关闭清焦点和各 UI 自身订阅 session.version 的既有行为。
  输入与输出页提示条靠近页面底部，任务页抬高到转发按钮上方。
- 设计依据：[Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)。

## 边界

- 只能访问 Android 对应用公开的音频端点；系统未公开的隐藏设备无法列出。
- “请求低延迟”不等于设备一定提供最低延迟；蓝牙、USB/HDMI 驱动与厂商策略均会影响结果。
- 保护上限：单设备 32 声道、最多 128 对完整转发；超限安全失败。

1.5 验证记录：18 项 JVM 单元测试通过；600×600 圆屏手表 compact-mode、compact-routes 均通过，包含真实两路启动/停止、运行时拒绝点击修改、首行实时参数与清空递补。普通手机 launch-tabs、route-rules、forward-start-stop 通过；600×600 无手表标识的 compact-mode 切换验证保留了 2 条转发配置。已目视检查圆屏 600×600 与 384×384、深色和 130% 字体，停止/零配对禁用画面均有截图。证据目录：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa\wear-20261010`。
手机 600×600 的 compact-routes 完整流程也通过：两种列表选择顺序、多配对启动/停止、运行锁定、参数显示、清空递补和新增模式。最终手表恢复 600×600、浅色、100% 字体，并在任务页保留一个已就绪且未启动的配对；手机恢复原分辨率。两设备崩溃缓冲为空。

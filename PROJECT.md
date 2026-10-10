# HighSpeedIEM

多通道实时音频转发：把麦克风收录的声音低延迟地转发到扬声器/耳机/其他输出设备。
包名 `cn.xiaotao29.HighSpeedIEM`，版本 `2.5`（versionCode 16），显示名见 `app/src/main/res/values/strings.xml`。

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
非触控专项：`keyboard-phone, keyboard-overflow-phone, keyboard-compact-phone, keyboard-compact-small-phone`。
动效专项：`visual-motion`，实际注入按下/取消事件并对比截图，检查输入/输出卡片的原生水波纹、范围裁切、取消后无残留及不启动音频。
布局专项：`adaptive-layout`，同一手机上的横竖屏、1/2/3 列、150%/200% 字体、600px 模式切换与滚动锚点恢复。
后两项均在手机上通过主线程 `Activity.setContent` 临时加载实际 `CompactFlow`，并在 `finally` 恢复手机界面；
small 场景仅在组合内提供圆屏配置和 220dp 内容区域，不启动手表模拟器，不更改手机系统分辨率。
输出逐场景 `=== SCENARIO <名称>: PASS/FAIL - 说明` 与 `=== SUMMARY`。
默认只在正常分辨率的手机模拟器执行，不使用 `all`（会包含 `compact-mode`）。
可在手机上临时加载实际极简组件，或通过降低手机分辨率验证生产模式切换；
电视、手表等平台的模拟器仅在用户显式要求时测试。

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
选中通道用容器配色表示；按键焦点用当前控件自己的边框表示。内部控件聚焦时，整张卡片不再跟随画框。

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
转发表使用 Kotlin 不可变行与列表，通过 Compose `mutableStateOf` 一次性发布整表；固定行 ID 同时用于编辑定位和原生懒加载列表/网格的 key。
新增、清空、去重与禁用/消失通道的剪枝统一由 RouteModel 维护，外部只能读取列表与字段，旧界面快照不会被原地改写。
所有选择入口共用运行/准备/清理锁与启用通道检查；已经删除的行或失效菜单回调不会重新插入转发。
`task-route-editing` 在正常手机直接操作下拉菜单，验证连续增删、单侧保留、前行删除、去重、展开菜单剪枝及实际启停；不通过切 tab 强制重建掩盖刷新问题。
竖屏底部中央的 Material 3 横向悬浮工具栏包裹 Split 按钮：主键“启动 N 个转发”（N=0 时禁用），副键打开系统后台运行保护设置。
工具栏在任务页列表上方独立悬浮，尺寸按键组内容确定，与底部 Navigation bar 保持 12dp 间距，外侧露出列表。
底层使用 LazyColumn，标题、状态和转发行均为列表项；列表底部留白按工具栏实测高度与间距计算，末行可完整滑到工具栏上方并操作。提示条也根据实测高度避让工具栏。
准备、运行和停止清理期间列表禁止编辑。
每行显示已打开流的实际参数（采样率、编码、声道、共享模式、burst、xrun 等），约每 3 秒更新。

**参数**：采样率、采样深度、录音预设默认自动由系统协商；同一设备多声道共享一组设置，
只有通道 0 可改。独占默认关闭并需实际开流探测支持，不接受静默降级为共享。

**错误原则**：转发运行中发生致命错误时先停止全部转发、等待资源安全释放，
再显示原始错误信息（不重写系统的错误文本）。

## 三种自适应布局

窗口实测像素决定模式，按以下优先级分派，应用不锁定设备方向：

1. 任一窗口边长 ≤600px，或手表类型：低分辨率 CompactFlow。
2. 其他宽度大于高度的窗口：横屏 NavigationRail + 内容区域。
3. 其余窗口：竖屏固定 TopAppBar + 底部 NavigationBar。

横屏导航使用原生 Material 3 NavigationRail / NavigationRailItem，无展开入口。
左栏宽度 96dp，app 图标和名称靠顶部，三个导航目标整体居中；品牌高度实测后
为导航组保留对称空间，大字体或矮窗口中的导航组可以独立滚动，避免与品牌重叠。
横屏取消固定顶部标题和底部导航，右侧内容占满系统安全区内的剩余空间。

输入、输出、任务三个标准页面共用卡片列表框架；竖屏设备页沿用滚动 Column，任务页使用 LazyColumn；横屏使用
LazyVerticalGrid / GridCells.Adaptive，最小卡片宽度 320dp 并随字体比例增大。标题/副标题/状态提示跨满整行，
卡片按原列表顺序从左到右、从上到下排列，内容和控件沿用竖屏组件。
使用稳定 key、contentType 和原生保存的滚动状态，切页保留位置；横竖屏切换时同步可见项索引和偏移。
设备页记录原生测量的标题/卡片高度，用于 ScrollState 与 LazyGridState 的滚动位置转换。
设备能力和频谱仍使用绑定卡片/控件的原生 TooltipBox / RichTooltip，位置避开系统栏、
刘海和窗口边缘；能力弹窗最大宽度 480dp，窄窗口继续收缩，长表格独立滚动。

横屏任务页的工具栏使用原生 VerticalFloatingToolbar，左侧全高度区域独占宽度，
转发对列表在其右侧布局，卡片无法滑到工具栏下方。电池键在上、转发键在下，图标在文字上方，
启动/停止文字竖排，多位计数保持完整；辅助功能读取自然语序。原生 Button 自适应内容宽度，
极矮窗口或大字体时工具栏可独立滚动。Compose 的 SplitButtonLayout 仅提供横排，
因此竖向键组采用两个原生 Button 和相邻圆角组成；竖屏仍保留原 SplitButtonLayout。

三种模式共享同一个 MonitorSession 和 RouteModel，路由与运行状态在配置变化中保留。
标准页和 compact pager 的 UI 状态由同一 rememberSaveableStateHolder 保存；进入 compact
模式遵循原有任务配置行为并释放设备预览/铃声测试。Scaffold 统一处理系统安全区，
内容消费已应用的 inset，避免嵌套原生控件重复留白；横屏提示条无需避让底部工具栏。

`adaptive-layout` 专项在一台手机模拟器上改变方向、像素密度、字体与窗口尺寸，验证三种
模式、1/2/3 列布局、导航品牌/居中、第二列弹窗、工具栏全高留空、末行可访问和大字体控件。
测试在 finally 中还原尺寸、密度、字体、方向和任务表。低分辨率实际交互另外通过
`compact-binding-phone` 在主线程临时加载 CompactFlow、finally 恢复 MonitorApp 验证。

组件依据：[Android NavigationRail](https://developer.android.com/develop/ui/compose/components/navigation-rail)、
[原生自适应网格与跨列标题](https://developer.android.com/develop/ui/compose/lists)。

## 低分辨率与手表模式

屏幕任一物理维度的像素 ≤600，或设备为手表（UI_MODE_TYPE_WATCH）时，统一显示 Material 3 三页界面：

- 第 1、2 页分别是任务表首行输入、输出下拉菜单的完整选项列表，包括空白项及全部启用通道。
  列表使用 Material 3 ListItem；显示设备 ID、通道编号、设备类型，选中项使用 primaryContainer 高亮。
  页面没有标题、说明或额外操作控件；点击选中项保持选择，空白项仅清除当前页对应的一侧。
- 第 3 页信息模块占用中号按钮上方的全部剩余空间：顶部复用手机任务页的状态提示框，下面显示映射转发对的输入与输出实际运行参数，
  内容可独立滚动，未运行时参数区域保持空白。底部居中放置一个 Material 3 中号填充启动/停止按钮，默认高度 56dp。
  启动文字为“启动 N 个转发”，N 是整张任务表的有效配对数；启动、停止与手机共用同一会话与前台服务。
- 底部的 `CompactPageIndicator` 包含三个圆点，当前页高亮；每点都有独立的 48dp 选择区域与 Tab 语义，
  点击或确认键可切页，左右方向键也可直接切页。圆屏额外保留底部安全边距，焦点框不被圆形边缘裁切。
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
  首尾各停留 1.5 秒，双向平滑滚动，展开菜单时暂停，值或宽度变化后从开头重新显示。
  系统关闭动画时保持起始文字，避免瞬间反复跳动。边框、标签和箭头保持固定。
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
- 2.4 恢复 Material 3 原生水波纹；触控反选不残留焦点框，各 UI 自身订阅 session.version。
  下拉关闭时，触控清焦点，按键操作恢复到原入口。
  输入与输出页提示条靠近页面底部，任务页抬高到转发按钮上方。
- 设计依据：[Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)。

## 视觉动效（2.4，2026-10-10）

- 主题使用 `MotionScheme.standard()`，颜色/透明度使用 effects 参数，尺寸/位置使用 spatial 参数；
  沿用 Compose 的系统动画时长缩放，不添加自定义动画引擎、强制振动或持续装饰动画。
- 输入/输出卡片使用原生 `Card(onClick, enabled)`，按下波纹沿 24dp 圆角裁切；
  选中与取消的容器/文字颜色平滑变化。内嵌复选框、参数框和能力入口保留独立操作及反馈。
- 标准导航页面淡入，离开的页面立即释放组合与音频预览，避免退场动画保留旧焦点、弹层或点击入口。
  原生 SaveableStateHolder 保留各页滚动位置，返回时不恢复音频测试。加载遮罩淡入淡出，加载圈由原生控件绘制。
- 转发行按固定行 ID 使用 `animateItem` 淡入/淡出并平滑重排；状态徽标与启动/停止文字平滑调整宽度，
  操作图标使用原生 Crossfade。运行参数只在出现/消失时展开/收起，不给实时数值叠加循环动画。
- 紧凑界面的页点点击和左右键使用原生 Pager 动画，与滑动手势一致；当前页点平滑变为短胶囊，
  保持独立 48dp 点击区与 Tab 语义。通道列表使用原生 ListItem 选中反馈及 animateItem。
  中号启动按钮使用 `ButtonDefaults.shapesFor` 的原生按压形状及默认 elevation。
- 下拉菜单、箭头、复选框、导航选中指示、Rich tooltip 和 Snackbar 沿用控件自带动效；
  焦点立即跟随真实操作目标，不延迟键盘与无障碍反馈。系统动画关闭时可照常选择、切页和启停。

依据：[Material Motion](https://m3.material.io/styles/motion)、
[MotionScheme](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)、
[Compose 原生动效](https://developer.android.com/develop/ui/compose/animation/composables-modifiers)。

## 非触控与无障碍（2.3）

- 只依赖方向键、确认（Enter / DPAD_CENTER）、返回、首页。上下按阅读顺序逐个遍历，左右用于横向控件；
  极简界面的左右键切页。到列表之外仍可继续到导航与启动/停止按钮，无需触摸、Tab 或特殊遥控器键。
- `KeyboardFocus.kt` 只装饰原生控件已有的焦点节点，不叠加 `focusable`；使用 `isFocused` 区分自身与子控件。
  只在按键模式及当前 Android 窗口中画 3dp 轮廓，弹层不会与底层入口同时画框。填充主按钮用 onPrimary 轮廓保证对比度。
  TalkBack 的触摸探索启用时采用系统朗读焦点框，不再叠加应用自绘轮廓；停用后自动恢复按键轮廓。
  禁用控件不进入按键焦点；纯文本、装饰图标、加载遮罩及包含操作控件的滚动容器不增加冗余停点。
- 只读下拉框在编辑器处理前拦截方向键，避免移动光标或吞掉按键；展开后直接聚焦当前选项，
  菜单项有明确的前后焦点顺序，每次上下移动一项。空白选项会读出“清除…”，返回不改选项，选择/返回均恢复入口焦点。
- 设备能力与实时频谱只在确认后打开，不因移动焦点自动弹出；弹层提供可聚焦“关闭”，支持返回。
  频谱关闭后同步取消预览并在资源解锁后恢复入口焦点，退出页/后台继续遵循原音频资源生命周期。
- 长设备列表随焦点自动滚动；任务列表的 `BringIntoViewSpec` 按悬浮工具栏实测高度扣除遮挡区域，
  保证按键聚焦的末行下拉框完整显示在工具栏上方。
- 极简列表的上下键显式加载相邻 Lazy 项后聚焦，分页入口能到达完整列表的首尾项；
  后台缓存页不注册当前页的焦点路径。圆屏页点留有安全边距，状态与参数一起滚动，给小屏保留阅读空间。
- 独立且溢出的设备能力/极简运行信息保留一个可滚动焦点，上下读内容，到边缘继续遍历；左右可离开该区域
  （极简界面优先执行切页）。没有溢出的文字区域不增加焦点停点。
- 无障碍描述含设备 ID、通道及动作；复选框、菜单和页点保留原生角色/选中/禁用语义，
  下拉框补充当前值与展开状态，卡片补充测试状态。标题标记 heading，状态徽标使用 polite live region；
  装饰图标和重复的设备标题不再重复朗读。加载遮罩只吸收指针，不伪装成可点击按钮。
- 专项测试从实际 Compose semantics 枚举焦点，以真实遥控键注入遍历/操作，检查唯一停点、同一窗口内重复边界、
  正反向路径和禁用跳过；弹层通过公开 `WindowInspector` 读取当前窗口的真实焦点，避免 UiAutomation 焦点缓存滞后造成误判。
  不使用点击坐标或语义 performClick/requestFocus 代替被测导航。

设计依据：[Android 焦点目标](https://developer.android.com/develop/ui/compose/touch-input/focus/focus-target)、
[焦点行为](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior)、
[Compose 无障碍](https://developer.android.com/develop/ui/compose/accessibility)。

## 边界

- 只能访问 Android 对应用公开的音频端点；系统未公开的隐藏设备无法列出。
- “请求低延迟”不等于设备一定提供最低延迟；蓝牙、USB/HDMI 驱动与厂商策略均会影响结果。
- 保护上限：单设备 32 声道、最多 128 对完整转发；超限安全失败。

1.5 验证记录：18 项 JVM 单元测试通过；600×600 圆屏手表 compact-mode、compact-routes 均通过，包含真实两路启动/停止、运行时拒绝点击修改、首行实时参数与清空递补。普通手机 launch-tabs、route-rules、forward-start-stop 通过；600×600 无手表标识的 compact-mode 切换验证保留了 2 条转发配置。已目视检查圆屏 600×600 与 384×384、深色和 130% 字体，停止/零配对禁用画面均有截图。证据目录：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa\wear-20261010`。
手机 600×600 的 compact-routes 完整流程也通过：两种列表选择顺序、多配对启动/停止、运行锁定、参数显示、清空递补和新增模式。最终手表恢复 600×600、浅色、100% 字体，并在任务页保留一个已就绪且未启动的配对；手机恢复原分辨率。两设备崩溃缓冲为空。

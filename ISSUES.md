# HighSpeedIEM 待解决问题（交接）

2026-10-09 第二轮会话结束时的状态。原 P0（会话锁 flake）与 P1（行焦点环）均已闭合并全量验证，根因与修复记录在下方"已解决"一节，其中的 Compose 强跳过陷阱是本项目的头号架构约束，改 UI 前必读。

## 物理设备待复测

3.0 已将每个单输出输入改为输出回调非阻塞直接读取，移除该路径的应用队列和第二回调线程；
跨输出扇出改为按块队列，并恢复卡顿后增加的缓冲。自动输入预设使用系统 VoicePerformance。
模拟器只能验证实际开流、稳定性和资源清理；用户报告的物理电视相对系统原生返声的可感延迟，
仍需在同一麦克风/输出设备/音量设置下对比 2.9 与 3.0。没有实际电视往返延迟的毫秒测量；
不能把无应用队列、模拟器帧数或系统申请 LowLatency 当作真机延迟达标。
建议先保持自动采样率/格式/预设，设备确实支持时再测试独占；厂商系统返声是否使用
应用不可访问的 DSP/HAL 直通路径目前未知，不能保证第三方 AAudio 与其相同。

2.9 已修复设备变化时运行列表被改写、设备页过期测量数组越界、并行输入探测/静音误判、
Oboe 错误回调与释放竞态，
并通过手机模拟器的模拟设备变化与真实开流测试。多个 USB 外设的实际驱动插拔尚未复测；
需在报告问题的手机上检查系统公开设备清单与 app 是否一致，不能把模拟回调当作物理 USB 验收。

### 2.9 设备生命周期重构（2026-10-10）

- 空闲用 AudioDeviceCallback 原生通知与 200ms 防抖合并，串行、仅开关流验证；丢弃过期结果。
- 转发从准备到清理期间冻结列表/探测，AAudio 致命错误停止全流，结束后阻塞补刷新。
- **已实际复现设备页崩溃**：`ChannelList.onSizeChanged` 恢复长度 7 的旧高度数组后写 index 7，
  抛 ArrayIndexOutOfBoundsException。rememberSaveable 的 inputs 不会验证恢复值；现以 Saver
  校验通道 key 列表与长度，拒绝过期/旧格式缓存，并保护晚到的测量回调。
- 流回调使用共享所有权，错误状态独立保活，全部流 close 后再 reset 各流指针。
- **取代下方历史 watchdog 行为**：超时只提示，不强行清除占用锁；铃声 finally 完成后才能开始下一次开流。
- 多 USB 列表无按名称/类型去重及数量上限；系统未公开端点的限制保留，详见 PROJECT.md。


### 2.2 任务转发表状态重构（2026-10-10）

- 修复任务下拉框选择后新增行不出现、清空最后一侧或合并重复配对后闪退。2.1 的普通可变列表被 LazyColumn 缓存行数后仍原地增删，测量时触发 `itemsIndexed` 的 `ArrayList.get` 越界；切 tab 重建页面只能暂时掩盖问题。
- RouteModel 现在只有一个 Compose `mutableStateOf` 整表状态。行是 Kotlin 不可变 data class，固定 Long ID；编辑、去重、清空和通道剪枝计算新列表并一次性提交，列表与映射 ID 同步更新。外部不能直接改行字段或增删列表，不再公开 normalize。
- TaskPage 在订阅作用域读取转发表、启用通道、编辑锁及运行参数，再向 LazyColumn 和纯展示子组件传值；列表使用行 ID 作为 key。RouteCard、RouteSide 不再读取 MonitorSession 非快照字段，无需每个子组件自行订阅 session.version。其他仍直接读取会话字段的 UI 沿用原订阅规则。
- 手机与其他界面共用同一选择入口，校验当前通道是否存在、是否启用以及运行/准备/清理锁。根据 ID 查找最新行，已经删除的行及失效菜单回调不能重新插入配置；菜单禁用时通过原生 LaunchedEffect 关闭并清理焦点。
- 保留新增空白行、单侧清空保留另一侧、完整配对去重、共享端点、多条部分配对、只启动完整配对、固定初始映射及原有工具栏/运行参数效果。
- 26 项 JVM 测试通过（21 个转发表测试 + 5 个设备类型测试），包含无 session.version 的 snapshot 观察通知、旧列表/行保持不变、删除后的迟到回调与 200 轮连续编辑。
- 最终完整手机回归 13/13 通过，覆盖 native-selftest、launch-tabs、device-validity、selection-preview、spectrum-tooltip、ringtone-test、ringtone-native-drain、route-rules、task-route-editing、task-toolbar、forward-start-stop、error-stop、rotation；日志为证据目录中的 `final-phone-regression-v4.txt`。铃声测试按设备 ID 定位标题，避免同名设备的相同通道号被误选；提示条关闭重试覆盖每次节点失效。
- 新增正常手机 `task-route-editing` 场景：真实下拉框连续 8 轮新增/清空、两种选择顺序、前行删除与存活行 ID、去重、部分配置、无效/禁用通道、展开菜单剪枝、实际启动/停止、编辑锁、运行参数及切 tab 保留，专项通过。手机长列表和铃声完整流程也通过。
- Debug x86_64 与 Release 四 ABI 构建及 release vital lint 通过。交付文件 `C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\app\outputs\apk\release\cn.xiaotao29.HighSpeedIEM_2.2.apk`，版本 2.2 / versionCode 13；沿用既有 debug 签名证书，APK v2 验签通过，armeabi-v7a/arm64-v8a/x86/x86_64 齐全。
- 仅在手机 emulator-5554（API 37、x86_64、1080×2400）测试；未使用低分辨率、电视或手表。测试证据位于 `C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa-route-refactor-2.2`。
- 最终安装四 ABI 签名发布 APK，再实际操作新增/清空、输出优先配对、第二配对、重复合并、启动/停止，双侧 48000 Hz 参数与 xrun=0 显示正常。崩溃缓冲为空，无活动转发服务；模拟器保持前台任务页、一条就绪配对、转发停止。详细验收见证据目录 `verification.md`。

### 视觉更新（2026-10-09，本轮）

当前视觉实现以 PROJECT.md 的“视觉设计”一节为准；下方早期样式记录仅作历史参考。
频谱已改为柱状并收窄至原宽度 85%；统一标准三页的色彩角色、标题/状态层级、圆角、间距、
下拉框与菜单。设备能力信息改为 Rich tooltip 两列表格，仍保留原完整数据；输入参数改为两列加整行录音预设。
测试键和转发键禁用时撤去阴影，采用预合成的不透明中性底色；可用态仍悬浮 6dp。
测试键开始/停止统一 primary 色调与固定胶囊形，保留无波纹及反选/下拉清焦点的既有偏好。
测试键外层语义现在显式标记 disabled，避免无障碍层误认为禁用按钮可用。
任务菜单保留完整设备名称，收起值显示通道与设备 ID；菜单使用原生控件并放宽至 240–360dp。

已修正文档中 instrumentation 的组件路径：测试 runner 与 debug app 在同一个 APK，实际入口为
`cn.xiaotao29.HighSpeedIEM/.test.UnifiedTestInstrumentation`，不是单独的 `.test` 包。
本轮证据位于 `C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\visual-qa-20261009`。
手机验证：Android 17 / API 37、x86_64、1080×2400；9 个 JVM 单元测试和 9 个常规仪器场景通过。
另以真实 UI 检查了柱状频谱、禁用点击、选中/反选、铃声开始/停止、转发开始/停止、完整运行参数、
设备能力展开、原生下拉菜单及焦点清理；浅色/深色和 130% 字体截图均已检查。
频谱实测 847px / 原 996px = 85.04%（整数像素取整）；放大字体下转发两列改为上下排列。
输入页提示条额外抬高至频谱之上，避免覆盖图表。模拟器已恢复浅色、100% 字体及自动旋转。

仪器回归 9/9 PASS（低分辨率 compact-mode 场景按用户要求默认不跑，见"环境备忘"）；手工 adb 验证输入/输出页连点往返、铃声测试键、任务页实时刷新、焦点环全部通过。

## 已解决（2026-10-09）：P0 点击吞没 + P1 焦点环

### 真正的根因：Compose 强跳过模式下"转发但未使用的 version 参数"被剔除出重组比较

**现象链**（曾误诊为会话锁 flake）：选中卡片后无法反选；输出页貌似多选；任务页/铃声按钮不随状态刷新、切 tab 才变。

**实测定位过程**（临时日志已清理，方法记录）：
- 卡片 onClick 加日志：第二次点击**连 onClick 都不触发**，但 pointerInput(Initial pass) 证明 down/up 事件已送达卡片节点，且当时所有会话锁均为 false（preview 开流实测仅 ~90ms，从未卡死）；
- interactionSource 监听：clickable 连 PressInteraction.Press 都不发射；
- 对照 foundation 1.12.1 源码（Clickable.kt）：新版 ClickableNode 在 `enabled=false` 时**仍消费 DOWN 但不发射 Press/onClick**——与观测完全吻合；
- 决定性 A/B：在 DeviceCard 体内加一行读取 `version` 参数的日志 → 点击 3/3 正常；删掉 → 1/3。

**机制**：Kotlin 2.3.21 + Compose 编译器强跳过（strong skipping，默认开启）下，可组合函数的**未在函数体内使用的参数不参与调用点相等性比较**。`DeviceCard(..., version, ...)` 的 version 只进签名不进函数体 → 会话 bump version 后 DeviceCard 被永久跳过 → 体内 `session.forwardingLockedPublic` 等非快照读取停留在旧值（选中瞬间恰好是 previewOpening=true）→ `clickable(enabled=false)` 把后续点击全部静默消费。原 Java 工程用 View 体系 render() 全量刷新，无此问题；这是重写版特有的架构陷阱。SpectrumCard 之所以一直正常，是因为它体内真的执行了 `session.version.collectAsState().value`（快照订阅，直接失效自身作用域，不依赖父级传参）。

**修复模式（今后所有新 UI 必须遵守）**：
> 凡读取 MonitorSession 非快照状态的可组合函数，必须在**自身作用域**执行 `session.version.collectAsState().value`（可赋给 @Suppress("UNUSED_VARIABLE") 的 val），禁止依赖父级转发 version 参数。快照状态（如 RouteModel.Row 的字段）自带订阅，不受影响。

已按此模式改造：InputPage / OutputPage / DeviceCard / TaskPage / RouteCard / RouteSide / SplitStartButton / RingtoneFab / CompactFlow / CompactStartPage，`version: Int` 参数已全部移除。

**附带修复**：DeviceCard onClick 内恢复原版工程的实时锁检查（`if (!session.forwardingLockedPublic)` 才执行选中逻辑），防御合成态落后一帧的边界。

### 会话层防御性加固（保留）

虽然锁卡死并非本次根因，MonitorSession 仍按原修复方向加固（防御模拟器/真机上原生开流挂起等未知时序）：
- `updatePreview` / `stop` 的 main.post 改用**专属操作计数**（previewOperation / stopOperation）判定"最后一个落定的操作"，previewOpening / busy 不再可能因共享 generation 被无关操作 bump 而永久滞留；
- 历史 watchdog 曾在 previewOpening>8s、busy>20s、scanInFlight>60s 强制解锁；**2.9 已替换**为只提示超时并保留资源锁，直到真实清理完成，避免设备热插拔时重叠开流。正常铃声播放仍无总时长限制，工作线程检查连续无进度超时。
- 验证：连点同卡 5 次全部往返、仪器 selection-preview / ringtone-test / forward-start-stop PASS。

### P1 焦点环（已按用户最新要求调整）

点击卡片出现 2dp 主色边框；**反选后边框立即消失**（用户 2026-10-09 明确要求：取消输入通道测试后不保留焦点框）。实现：边框条件仅 `selected || focused`，页面级 `tappedKey` 状态已删除，`.focusable()` 也已移除（旧记忆"必须保留 focusable 否则点击被吞"是误诊——吞点击的真凶是上述重组跳过；focusable 反而制造双重焦点目标，勿加回）。

### 下拉菜单选中样式（2026-10-09 调整）

所有 `OptionDropdown`（输入页参数、任务页路由通道）的选中项采用**背景高亮**（`SelectableDropdownMenuItem` + `MenuDefaults.selectableItemColors(selectedContainerColor = primaryContainer, selectedTextColor = onPrimaryContainer)`），不再使用尾部勾选图标。material3 1.5.0-beta01 的菜单 API 已重构：旧的 `DropdownMenuItemDefaults` 不存在，选中态用 `SelectableDropdownMenuItem`（需显式 import，同包显式导入风格下漏 import 会报 Unresolved reference）。

### 原生按压反馈与无残留（2.4，2026-10-10 用户要求）

- **恢复原生 ripple/按压指示**：本次用户明确喜欢卡片选中波纹并要求全面优化视觉动效，取代 2026-10-09 的无波纹偏好。删除全局 `NoIndication` 覆盖，通道卡片改用原生可点击 `Card`，由圆角 Surface 处理水波纹、按压/聚焦状态与取消反馈。主题和标准过渡采用 `MotionScheme.standard()`，不叠加自绘波纹、缩放抖动或 tapped 状态。
- **下拉锚点清焦点**：`OptionDropdown` 在选中项 onClick 与 onDismissRequest 两条关闭路径都 `LocalFocusManager.current.clearFocus()`，否则锚点保留焦点态主色边框（实测复现过的残留）。
- 卡片选中以平滑容器配色表示；键盘焦点轮廓只跟随真实目标，取消按压不遗留状态，也不启动音频测试。

### 下拉菜单实现细节（2026-10-09 定稿：全原生）

- **锚点为原生 `OutlinedTextField`**（readOnly + singleLine + `menuAnchor(PrimaryNotEditable)`）：标签浮在轮廓线上、轮廓线在标签处的缺口均由控件原生绘制。**曾尝试自定义 Box 锚点实现跑马灯，色块遮线方案与卡片背景色（含选中态 secondaryContainer）不匹配导致外观丑陋，已按用户要求撤回——下拉框一律原生实现，不再手工模拟。**
- **跑马灯已放弃**：OutlinedTextField 原生不支持内容跑马灯，实现它必须自定义锚点，与"原生优先"冲突；过长已选值按原生行为单行省略号截断。如将来确需跑马灯，需重新引入自定义锚点并解决背景色匹配问题（用户决定）。
- **菜单项**：`SelectableDropdownMenuItem` 背景高亮选中项（primaryContainer/onPrimaryContainer）；长文本默认自动换行（Text 不设 maxLines）。
- **性能**：`MenuDefaults.itemShapes()` / `selectableItemColors(...)` 已提升到 forEach 循环外（每项一次的主题读取降为每次打开一次）。打开延迟的瓶颈是**模拟器 Popup 窗口创建开销**（~0.3-0.8s，含入场动画），非应用层合成成本；真机会快得多。`ExposedDropdownMenu` 不暴露 `properties`/动画参数，无法进一步调优。
- **残留状态**：选中项 onClick 与 onDismissRequest 两条关闭路径都 `focusManager.clearFocus()`，否则锚点保留焦点态主色边框。

### 按键与悬浮层样式规范（2026-10-09 用户要求）

- **测试键（RingtoneFab）两态完全同一样式**：`ToggleButtonShapes(shape = pressedShape = checkedShape = RoundedCornerShape(50))` 固定胶囊形（默认 checked 态会 morph 成方形，必须覆盖）；未选中态借用选中态色调 `ToggleButtonDefaults.colors(containerColor = defaults.checkedContainerColor, contentColor = defaults.checkedContentColor)`，两态仅图标/文字不同。
- **阴影区分图层**：测试键 `Modifier.shadow(6.dp, capsule)`；任务页分裂按钮组两键各自 `shadow(6.dp, SplitButtonDefaults.leadingButtonShapesFor(SmallContainerHeight).shape)` / `trailingButtonShapesFor(...).shape`；频谱卡 `CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)`。
- **任务页悬浮布局**：1.9 起为 `Box { LazyColumn; HorizontalFloatingToolbar(align BottomCenter) { SplitStartButton } }`，列表底部留白按容器实测高度和间距计算，末卡可完整滑到容器上方。历史固定 88dp 留白与右下角独立按键组已移除。

## 已验证完成（勿重复排查）

- 输入页：连点同一卡 5 次，选中态逐次往返（uiautomator content-desc "已选中"标记）。
- 输出页：同卡往返 + 点击另一卡选中迁移（单选语义）+ 反选。
- 铃声测试键：无选中时视觉禁用（注意：外层语义 Box 在 uiautomator 中恒显 enabled=true，判断禁用要看截图样式或行为）；点开始立即变"停止测试"（无需切 tab）；自然播完保留选中；点停止走 stopOutputTestAndDeselect（清选中、按钮回禁用态）。
- 任务页：路由下拉选择后"启动 N 个转发"计数即时刷新；转发运行/停止按钮态即时变化。
- RouteModel / 频谱 / Snackbar 抬高 / 下拉框样式等此前条目仍然有效。
- 仪器回归 9/9 PASS（2026-10-09，修复后构建）。

## 手工 adb 验证的坑（本模拟器，已踩过）

- Git Bash 会转换 `/sdcard/...` 路径：所有 adb shell 命令前 `export MSYS_NO_PATHCONV=1`，否则 screencap/dump 写到错误路径。
- `uiautomator dump` 间歇返回 "null root node" 且**不覆盖旧文件**，必须确认输出含 "dumped" 才采信。
- `input tap` 前先 `dumpsys window | grep mCurrentFocus` 确认前台是本应用。
- 模拟器屏幕会休眠：截图全黑时先 `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard`。
- Pixel/Nexus Launcher 在 wm size 变化时崩溃循环，其崩溃对话框会抢前台吞掉全部输入（曾致 compact-mode 场景两次 FAIL、手工滑动全部失效）。对策：`settings put global hide_error_dialogs 1`（已设置，持久）+ 场景前 force-stop launcher；被抢焦点时点对话框 X 或 BACK 再 `am start` 拉回。
- 铃声测试约 4 秒自然结束，测"停止"要在开始后 1~2 秒内点。
- uiautomator 的 `focused` 属性是无障碍焦点，不能验证 Compose 输入焦点；看真实效果用截图。
- 每 shell 调用都是新会话：Gradle 需每次 `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`。

## 环境备忘

### 1.4 输出通道铃声测试（2026-10-09）

- 默认电话铃声完整播放，按块解码和写入，无 8 秒截断与整段 PCM 内存限制。
- 自然结束或默认铃声为空、无法读取、无音轨：释放占用并恢复测试前界面，保留通道选中与启用；无可播放资源显示提示。
- 手工停止：短间隔取消播放，释放所有资源；取消选中、保持启用，不改动转发表与参数。
- 自然结束等待实际播放尾部完成：AudioTrack 检查播放头，AAudio 同时检查软件队列与设备缓冲。
- 手机模拟器（1080×2400，API 37 x86_64）验证：24.25 秒铃声全部 1,164,000 帧播放完成，实际测试约 24.55 秒；停止按钮约 178ms 完成释放；三种资源缺失提示及原默认铃声重播通过。
- 原生 AAudio 专项：100ms 测试音连同设备缓冲约 203ms 排空、释放通过。另 9 项普通手机回归和 14 项 JVM 单元测试通过，崩溃缓冲为空。未执行低分辨率、电视或手表测试。
- 分批验证汇总：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\verification-1.4.txt`（11 项手机场景、14 项单元测试通过）；铃声专项原始结果：`ringtone-1.4-test.txt`。

代理：`http://192.168.2.1:38151`（非国内下载用）。构建 `./gradlew :app:installDebug -Pabi=x86_64`。回归命令见 PROJECT.md；**用户要求默认不跑低分辨率（compact-mode）场景**，优先保证正常设备逻辑：`-e scenarios native-selftest,launch-tabs,device-validity,selection-preview,ringtone-test,route-rules,forward-start-stop,error-stop,rotation`。

## 1.5 手表与低分辨率界面（2026-10-10）

- 统一三页：首行输入选项、首行输出选项、上半屏状态/参数与下半屏启动/停止按钮。无列表标题；32dp 三点页码指示器独立布局。
- 首行选择保留其余转发表；清空已占用的一侧移除当前配对并提升下一行，最后保留空白新增行。支持两种选择顺序、全表多配对计数、运行锁定。
- 使用 Material 3 原生可选择 ListItem、填充 Button 和共享 StatusBadge。圆屏内容增加随窗口变化的安全边距；长参数可滚动，小高度按钮优先保留操作文字。
- 嵌套可组合内容同样需要在读取非快照会话字段的作用域订阅 version，或在已订阅作用域读取值后显式传递给子控件。仅让外层重组不能保证被缓存的内容 lambda 更新。CompactFlow 的约束布局订阅自身状态；任务状态/参数先取值再传入子布局。
- 判定低分辨率使用 LocalWindowInfo.containerSize 的真实像素，避免 600px 在 dp 整数取整后被误判为 601px。手表标识依然优先于分辨率。
- 无障碍包装节点/按钮内文字可能报告 enabled=true；禁用行为验收使用真实点击后检查路由、启动状态及提示是否发生变化，并保留禁用画面证据。

## 1.6 手表中号转发按钮（2026-10-10）

- 手表/低分辨率第三页使用 Material 3 中号 Button：默认高度 56dp，原生中号内边距、24dp 图标和 titleMedium 字体。按钮底部居中，较窄屏隐藏图标，字体放大时允许自然增高。
- 信息模块占用剩余空间，保留状态框和参数滚动区。600×600、320dpi 圆屏的信息区从 134dp 增至 196dp；页面指示器保持独立 32dp。
- x86_64 编译、安装成功；compact-mode 和 compact-routes 均通过，包含真实两路启动/停止、首行参数、编辑锁定和清空递补。崩溃缓冲为空，最终前台停留第三页、已就绪 1 个转发、转发已停止。
- 证据：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa\wear-20261010\verification-1.6.txt`、`compact-running-1.6.png`、`handoff-1.6.png`。

## 1.7 输出列表自动播放（2026-10-10）

- 标准输出页移除测试按钮；点击卡片立即播放铃声，再次点击取消。自然结束或无可播放铃声时取消选中，保留启用。
- 播放及清理期间仅放开输出卡片的选择操作；参数和启用控件保持锁定。切换先取消旧 worker，再由其资源释放完成回调播放最新选择；快速 A-B-A 和取消待播放通道均不会重叠开流或留下选中。
- 切页、Activity 离开前台均取消当前与待播放请求，保留启用；返回不恢复播放。蓝牙权限回调校验通道和选择代次，避免迟到回调播放已取消的选择。
- 手机专项通过：24.25 秒 / 1,164,000 帧完整播放并释放；同卡取消约 180ms 释放；真实 UI 切换至第二通道且仅其高亮；快速切换、待播放取消、切页/后台、三种缺失铃声提示、原默认铃声重播均通过。
- 输出页列表和提示条不再保留测试按钮空间。当前逻辑以上述 1.7 说明为准，旧版本测试按钮及自然结束保留选中的记录仅为历史。
- 专项和回归证据：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa\phone-regression-1.7.txt`。
- 分批验证共 11 项手机场景通过，18 项 JVM 单元测试通过，崩溃缓冲为空。仅使用 emulator-5556（1080×2400，420dpi，API 37 x86_64）；最终停留输出页，无选中通道、播放已停止。汇总 `qa\verification-1.7.md`，最终画面 `qa\handoff-1.7.png`。

## 1.8 标准任务页分裂按钮样式（2026-10-10）

- 原两键确实共用全状态 6dp 阴影，并非启动/停止状态单独降低了 elevation；不同底色与形状可能影响阴影的视觉深浅。需要纠正的是填充按钮包括禁用态也强制悬浮，未采用 Material 3 填充按钮默认状态规范。
- 两键统一使用原生填充分裂按钮 LeadingButton / TrailingButton；仅保护设置键覆盖可用态 secondaryContainer / onSecondaryContainer，启动/停止键沿用 primary / onPrimary。阴影、禁用配色、形状、最小高度、内边距与字体均交由同一套原生默认配置。
- 已核对项目实际依赖 material3-android 1.5.0-beta01 的 SplitButton.kt 与 FilledButtonTokens.kt：常态、按下、聚焦、禁用阴影为 0dp，悬停为 1dp。较大字体可自然增高；全局无波纹偏好保留。
- 版本为 1.8 / versionCode 9；x86_64 编译安装通过。现有 launch-tabs、route-rules、forward-start-stop 三项仪器回归通过；真实 UI 验证浅/深色下零有效配对禁用点击、可启动/运行/停止状态更新和系统后台保护设置入口，运行时实际流参数可见。
- 仅使用正常手机 emulator-5556（1080×2400，API 37 x86_64），未运行低分辨率、电视、手表场景。崩溃缓冲为空。最终恢复浅色、任务页、一个已就绪配对、无转发服务。
- 验证记录与状态截图：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa-button-1.8\verification.md`；最终截图 `handoff-ready.png`。

## 1.9 标准任务页悬浮工具栏（2026-10-10）

- 使用实际依赖 1.5.0-beta01 中的原生 Material 3 `HorizontalFloatingToolbar`，常驻展开，按内容包裹原有 Split 按钮，底部居中，与 Navigation bar 保持 12dp 距离。保留容器默认形状、配色、内边距、最小高度及原生按键样式。
- 页面的底层仅为一个 LazyColumn，标题、状态和路由卡都是列表项；顶部工具栏用独立图层绘制，外侧可见列表。`onSizeChanged` 测量容器高度，列表底部 padding 与 Snackbar 同步避让，取代固定 88dp/96dp 留白。
- 调试入口新增 `task-toolbar` 手机场景：13 行布局夹具只用于溢出验证，结束时还原原路由；检查容器非全宽、居中、与导航不重叠、滚动位置固定，以及真正滑至末尾后末行完整可见、输入与输出菜单均可打开关闭。
- 版本 1.9 / versionCode 10。构建与验证记录保存在 `C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa-toolbar-1.9`。
- 最终手机竖屏浅色 100% 字体、横屏浅色 100% 字体、竖屏深色 150% 字体的 task-toolbar 均通过；既有导航、路由规则与开停回归通过。真实 UI 启动/停止、禁用启动及保护设置入口通过。应用无崩溃，最终恢复竖屏浅色任务页，一个已就绪配对、无转发服务。详见该目录 `verification.md` 和 `handoff-ready.png`。

## 2.0 工具栏容器统一阴影（2026-10-10）

- 工具栏原生 `expandedShadowElevation`、`collapsedShadowElevation` 共用固定 `TaskToolbarShadowElevation = 6.dp`，不读取可用性、运行状态或交互状态。容器阴影始终一致。
- 版本 2.0 / versionCode 11，x86_64 编译安装通过。手机 emulator-5556 的 task-toolbar 长列表回归通过；浅/深色各验证禁用、就绪、运行三态，并实际启动、停止，阴影保持相同配置。崩溃缓冲为空，停止后无转发服务。
- 模拟器保持运行，最终为浅色任务页、一个已就绪配对、转发停止。证据在 `C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa-shadow-2.0`。

## 2.1 手表两页固定映射转发对（2026-10-10）

- 原因：`RouteModel.setFirstSide` 在清空任一已占用侧时同时清空输入和输出；`normalize` 删除该行，而 CompactFlow 始终读取 `rows.first()`，导致另一侧消失或改为下一条配对。旧逻辑与旧测试中的“清空递补”规则已经被本次需求替代。
- 两页现在共享 `RouteModel.compactRow`，初始指向启动时唯一的空白添加行，按对象身份绑定。后台新增其他转发对、列表索引变化及 UI 重建不改变绑定；单侧清空仅清空对应字段。
- 两侧都空白后删除旧配对并绑定尾部空白新增行；绑定配对因冲突去重或通道禁用/消失被删除时同样重新绑定新增行，保留其他已有转发对。`normalize` 保持空白新增行的对象身份，避免无关的表更新破坏空白映射。
- 第三页参数同步读取映射行，启动数量仍按全表完整配对统计。版本 2.1 / versionCode 12。
- 新增 `compact-binding-phone` 调试场景：在正常分辨率手机临时加载实际 CompactFlow，执行两种单侧清空与高亮保留、双侧清空后的新增映射、后续行配置、真实冲突去重、通道禁用删除、两路启动/停止及运行锁定；结束后恢复手机界面。未更改生产模式判定。
- ListItem 的选中状态位于描述节点的外层可选择节点；测试沿相同 bounds 的父节点读取 checked/selected，不使用文字描述节点的状态代替整项状态。
- x86_64 构建、安装及 23 项 JVM 单元测试通过；`compact-binding-phone` 专项与手机 `launch-tabs, route-rules, task-toolbar, forward-start-stop, rotation` 五项回归全部通过。仅使用正常手机 emulator-5556（1080×2400、420dpi、API 37），未执行低分辨率、电视或手表模拟器测试；崩溃缓冲为空。记录与截图在 `C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa-binding-2.1`。

## 2.3 手机与手表非触控操作及无障碍（2026-10-10）

- 使用方向键、Enter / DPAD_CENTER、返回、首页即可操作。统一焦点指示框只包围当前窗口中真正聚焦的控件；父卡片与子控件不重复画框，禁用与装饰节点不进入按键路径。只读下拉框不再吞掉方向键，菜单逐项移动，选择或返回后恢复入口。
- 提示弹层只在确认时打开，提供可聚焦的关闭按钮；关闭频谱同步释放预览并在解锁后恢复入口。长列表随焦点滚动，末行避让悬浮工具栏；极简列表显式加载相邻 Lazy 项，避免重复停点与反向漏项，后台页不覆盖当前页的焦点路径。
- 极简分页点具备 48dp 操作区域与 Tab 选中语义，左右可切页，圆屏留有安全边距；运行信息有唯一的滚动操作，状态和参数一起滚动，小圆屏减少按钮外侧空白以保留阅读空间。
- 无障碍描述区分设备 ID、通道和动作；保留原生角色、选中、禁用信息，补充当前值与展开/测试状态，标题与状态更新有相应语义，重复标题和装饰图标不重复朗读。
- 版本 2.3 / versionCode 14。四 ABI debug / 已签名 release 构建通过，26 项 JVM 测试通过；release lint 为 0 错误、12 项既有警告。
- 仅在手机 emulator-5554（1080×2400、420dpi、API 37 x86_64）验证：18 类原有及新增场景均取得通过结果；手机输入/输出页分别遍历 39 / 16 个唯一可用焦点，菜单正反向逐项到达，13 行任务列表的 26 个选择框可达且末行不被工具栏遮挡，实际音频预览、测试音与转发开停通过。
- 实际 CompactFlow 在手机上临时加载，普通尺寸及 220dp 圆屏均通过输入/输出选择、分页、运行锁定与实际开停，始终在 finally 中恢复手机界面；另在深色、130% 字体下通过长列表与小圆屏按键专项。未启动电视或手表模拟器，未改动手机系统分辨率。
- 测试日志、界面树和截图：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa-keyboard-2.3`，场景汇总见 `passed-scenarios.txt`，深色大字体见 `dark-font-130-final.txt`。
- 最终 release 通过纯按键建对、真实启动/停止、首页与恢复前台；TalkBack 17 实际绑定后通过设备能力入口、弹层确认与返回，并目视确认不叠加应用轮廓，停用后轮廓自动恢复。测试验证语义与操作，未逐字人工验收合成语音。
- 最终安装已签名四 ABI `cn.xiaotao29.HighSpeedIEM_2.3.apk`；手机保留浅色、100% 字体、原始分辨率、一个已就绪配对，无转发服务，崩溃缓冲为空，无障碍设置恢复测试前值，模拟器继续运行。详见该目录 `verification.md`、`release-key-results.txt`、`handoff-ready.png`。

## 3.1 整体性能调优（2026-10-11）

- 高频 AppOps 权限检查和通信路由配置/清理移入串行音频线程；空闲停止轮询，实际端点统计仅前台任务页约 3s 读取，统计独立发布，避免全局重组。局部状态快照过滤重复值，频谱仅更新绘制，缓存 FFT 系数和图表资源，减少重复容器、无障碍监听和菜单焦点对象，长值仅聚焦时滚动。
- Release 启用 R8 与资源裁剪，四 ABI 签名包从 14575633 缩至 2833232 字节。实际 JNI 频谱、铃声、转发开停与后台返回验证通过；仅用手机模拟器，保持前台就绪并停止转发。
- 38 项 JVM 测试通过；21 类连续回归有 18 类通过，任务编辑独立重跑通过。启动瞬时加载层观测、铃声自动点击两项在基线 3.0 同样失败，正式包独立实际操作通过，原自动化失败证据保留。
- 未将 Debug 帧耗时当作正式包性能。正式包 18 次切页 P95 均为 18ms，尚无稳定切页改善证据；Perfetto 转发 10s 窗口主线程 Binder 69 次降为 1 次。后台切换仍有少量缺帧，未宣称解决电视真机卡顿或达到零缺帧。
- 全面改为竖屏 LazyColumn 的试验导致嵌套下拉框反向按键重复停点，已撤回；保留现有可靠的竖屏焦点路径和横屏原生懒加载。
- 版本 3.1 / versionCode 22；详细测量、错误、签名和产物见 [性能调优验收-3.1.md](性能调优验收-3.1.md)。

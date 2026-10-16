# HighSpeedIEM（高速耳返）v4.0

单一输入设备 → 单一输出设备的低延迟音频转发（耳返）应用。v4.0 为完全重构版本：
Kotlin + Jetpack Compose（Material Design 3）前端，C++ 直接调用 AAudio（不经 Oboe）后端。

## 架构

- **前端**（`app/src/main/kotlin/cn/xiaotao29/HighSpeedIEM/`）
  - `MainActivity` / `MainApplication`：单 Activity；权限、电池优化入口、前台服务启动。
  - `audio/RelaySession`：唯一内存态会话。设备列表用 `mutableStateMapOf<Int, AudioDeviceInfo>`
    由 `AudioDeviceCallback` 增量维护 + 启动时 `AudioManager.getDevices` 全量拉取覆盖；
    选中设备（全局各至多一个输入/输出）、每设备开流参数、混音路由、转发状态全部为
    Compose 快照状态，UI 自动订阅，无版本号转发。
  - `audio/NativeRelay`：JNI 桥（阻塞式 start/stop，实时 setMixer，200ms 轮询 streamInfo）。
  - `ui/`：三个 tab（输入设备 / 输出设备 / 转发控制）。设备卡片（图标 + 标题 + 副标题 +
    能力/测试/参数三键）、全屏参数对话框、混音 Basic dialog、实时参数 tooltip、
    开流期间全屏阻塞遮罩、Snackbar 报错原文（换行显示）。
    混音选择框使用 Material 3 BasicAlertDialog + Surface、ListItem/Checkbox，
    固定标题/底部按钮与贯穿分隔线，中间单列滚动；整行具备复选框语义与键盘焦点。
    选择先保存在可恢复草稿中，取消/返回/点外部不改变路由；确定时整张掩码一次更新，
    不逐项调用原生引擎。弹窗状态位于横竖屏布局分支之外，旋转/重建保留未确认选择。
    错误按带唯一 ID 的队列保存，显示完成或手动关闭后才消费；界面进入后台或重建时
    保留未读消息，回到前台继续显示，连续相同错误不会互相覆盖。
  - `service/ForwardingService`：前台服务（microphone + mediaPlayback + 部分唤醒锁），
    常驻通知点击打开转发控制 tab；最近任务清除即停止转发并释放资源。
- **后端**（`app/src/main/cpp/`）
  - `RelayEngine`：输出流 `setDataCallback` 回调内 `AAudioStream_read` 拉取输入流
    （回调驱动转发），每次回调均使用零超时非阻塞读取，短读尾部补零。
    不使用输入帧计数作为读取门槛：独占 MMAP 输入需要在 read 内处理服务时间戳，
    首次 read 前计数可能一直为零，门槛会导致永久静音。
    每输出通道按位掩码选择 0..N 个输入通道做逐帧平均混音，
    路由以始终无锁的 32 位原子掩码发布，回调复制到固定大小的栈内快照；
    正常混音路径不获取互斥锁，也不分配或释放堆内存。最多支持 32 个输入/输出声道。
    启动时先用零超时读取清空输入积压（20 个读到数据的回调），期间输出静音；
    每次最多读取一个输入缓冲容量，避免持续输入导致清空循环无界。
    请求低延迟模式时将实际输出缓冲设为两个 burst，由系统裁定实际值；
    启动稳定后每 0.25 秒检查输出欠载，若计数增加则增大一个 burst，最多到容量上限。
    普通/省电模式保留平台的实际缓冲大小，用户设置的缓冲容量仍用于开流。
  - 开流：输入先开先启动，输出随后；开流后校验两端实际采样率/格式一致（当前不支持
    重采样/格式转换），不一致立即关流报错。`AAudioStreamBuilder_setErrorCallback`
    回调式报错 → JNI → 主线程停止转发 + 清除选中 + Snackbar 原文。
    JNI 的 `onNativeError(String)` 接口和实现用 `@Keep` 保留，避免 release 压缩移除；
    设备移除先记录是否在使用，再清除选中项。开流期间收到停止请求会在开流结束后
    立即关流，完成前保持阻塞；移除无关设备不清除正在使用的混音配置。
  - 空间音频固定 `AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER`（API 32+）；MMAP 策略
    默认遵循平台 MMAP 策略；关闭时临时请求 NEVER（策略接口通过 dlsym 探测）。

## 构建

- 最低 API 27，目标 API 37.0；AGP 9.4.1（Kotlin 内置，勿再应用 kotlin.android 插件）+
  Kotlin Compose 插件 2.3.21；CMake 4.1.2 / NDK 30.0.16248370。
- 编译输出固定在 `C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/<module>`（根
  `build.gradle.kts` 重定向），源码树保持洁净。
- 发布 APK 含 armeabi-v7a / arm64-v8a / x86 / x86_64 四 ABI，签名 release，
  命名 `cn.xiaotao29.HighSpeedIEM_<版本>.apk`；调试可用 `-Pabi=x86_64` 单 ABI 提速。

## 音频回归测试

`RelayProgressTest` 使用真实 AAudio 流验证独占/共享输入、Float/I16、反复启停与
运行中切换混音。启动后 1.5 秒内不查询任何流参数，再检查输入和输出帧数，
避免实时参数面板的轮询掩盖启动问题。默认设备用于手机虚拟机，允许独占请求回退到共享。

```text
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <serial> install -r <app-debug.apk>
adb -s <serial> install -r -t <app-debug-androidTest.apk>
adb -s <serial> shell am instrument -w -r -e class cn.xiaotao29.HighSpeedIEM.audio.RelayProgressTest cn.xiaotao29.HighSpeedIEM.test/androidx.test.runner.AndroidJUnitRunner
```

实体机可增加参数 `-e inputDeviceId 23 -e outputDeviceId 2 -e inputChannels 2
-e requireExclusiveMmap true -e requireSignal true`，要求实际获得独占 MMAP 输入，
且输出回调确实写入过非零 PCM。`NonSilentOutputSeen` 仅在 debug 编译中提供，
release 不扫描音频样本。实体机需解锁，部分厂商系统还需允许测试应用显示前台界面。

2026-10-15 小米 2304FPN6DC 实测：修复前独占输入状态为 STARTED，但输入
FramesRead/FramesWritten 均停留在 0；移除读取门槛后，1.5 秒读取约 6.6–7.2 万帧，
独占输入 XRunCount=0，输出已混入非零 PCM。该检查验证数字音频链路，不能代替实际听音。

## 报错回归测试

`ErrorReportingTest` 的八项测试覆盖原文持续显示、重复错误排队、横竖屏/Activity
重建/后台恢复、使用中的输入和输出移除、无关设备移除不改变混音、开流期间设备
移除和原生错误。测试使用真实音频流，通过系统设备回调模拟移除事件；虚拟机测试
不等同于实体机实际拔出 USB。另需检查 release 的 R8 映射保留 `onNativeError` 原名，
并确认启动日志不再出现 `listener missing onNativeError(String)`。

## v4.46 延迟优化与验证

对比对象为 OboeTester 的 Echo Input to Output，用户反馈来自听感，没有端到端测量值。
主要差异是本应用原来只请求缓冲容量，未设置实际使用大小，本机输出默认值等于容量。
根据 [Android 官方低延迟建议](https://developer.android.com/games/sdk/oboe/low-latency-audio)
采用两个 burst，并参考 [Oboe FullDuplexStream](https://github.com/google/oboe/blob/main/include/oboe/FullDuplexStream.h)
清空启动积压；没有引入 Oboe 依赖，没有改动用户的设备、共享模式、采样率或音频用途。

2026-10-15，48 kHz、双声道 I16、小米 2304FPN6DC，输入 ID 23 独占 MMAP、输出 ID 2 共享：

| 输出参数 | 优化前 | 优化后 |
| --- | --- | --- |
| FramesPerBurst | 192 | 192 |
| BufferCapacityInFrames | 1536 | 1536 |
| BufferSizeInFrames | 1536（32 ms） | 384（8 ms） |

该差值是输出实际缓冲对应的时间，**不能等同于端到端延迟减少 24 ms**。
手机反复启停时还观测到 AAudio MMAP 输入时间戳迟到约 60–80 ms；替换回原版引擎的
对照测试同样出现，移除启动清空或延后缩小输出缓冲也未消除。其确切成因尚未确定，
当前优化不声称修复该系统/设备侧时间戳现象，最终效果仍需相同链路听音或硬件回环测量。

`LatencyBufferTest` 记录实际缓冲与启动丢弃帧，验证数据持续流动、密集路由更新及无效
路由边界；另验证普通性能模式不自动改变缓冲、显式容量设置仍生效。实体机可增加
`-e requireTwoBursts true` 检查平台确实接受两个 burst；其余设备只验证平台返回的有效值。
`BufferSizeInFrames` 和 `StartupFramesDiscarded` 也会出现在应用实时参数中，
用于区分容量、实际大小和启动清空行为。

验证结果：四 ABI debug/release 编译及 release 签名校验通过；手机虚拟机 13 项仪器测试
通过（含横竖屏），小米指定 ID 23/2 的 5 项测试通过，独占输入和输出的采样记录中
XRunCount=0、已输出非零 PCM。共享输入测试仍有输入 XRun，测试通过只表示数据流
持续推进，不能作为无爆音或端到端低延迟的证明。release Lint 为 0 errors / 8 warnings
（现有权限查询、资源/图标、依赖版本、备份和唤醒锁提示，本次未调整）。

## v4.56 混音对话框

按照用户提供的系统列表对话框参考图重新排版，沿用应用主题色和 Material 3 原生圆角、
色彩、文字及复选框组件，自动跟随深色模式。标题与取消/确定操作固定，长列表独立滚动；
滚动区受窗口可用高度约束，在横屏也保留操作按钮。
`MixerDialogTest` 验证取消/返回丢弃、确认多选/清空、Activity 重建和横竖屏保留草稿、
遥控器按键切换、32 声道滚动到底部并选中最后一项。
组件用法参考 [Android Dialog](https://developer.android.com/develop/ui/compose/components/dialog)
和 [Checkbox](https://developer.android.com/develop/ui/compose/components/checkbox)。

验证：手机虚拟机 10 项界面/报错测试通过；release 浅色/深色的横竖屏截图检查通过，
切换时弹窗与勾选仍保留。四 ABI release 编译与签名校验通过，Lint 0 errors / 8 warnings。

## v4.6 单行文字跑马灯

恢复混音弹窗标题、通道选项和操作文字的 `MarqueeText`，并统一导航标签、设备按钮、
状态标签、参数菜单及实时参数表的单行显示。共用 Compose `basicMarquee`，只在文字
超出可用宽度时自动循环滚动，能完整显示时保持静止；正文提示及 Snackbar 错误仍按段落换行。
本次版本名称按用户指定设为 4.6，versionCode 递增为 51。
验证：debug 混音交互回归 2 项通过；release 在系统字体 2.0 倍下，横竖屏相隔两秒
的截图均确认溢出标题发生位移，选项和按钮仍可操作；转发中的实时参数面板开关正常。
测试后恢复字体 1.0 倍及竖屏。四 ABI release 编译和签名校验通过，Lint 0 errors / 8 warnings。

## 设备类型映射

`AudioDeviceTypes` 维护 TYPE_* 数字 ID → 中文名与原始枚举名；详见 `设备类型映射.md`。

## v5.2 电视开流长时间等待修复

2026-10-16 在小米 MiTV-ASTP0（Android 9 / API 28）读取原版日志，使用输入
ID 29（USB-Audio - MI KARAOKE）及输出 ID 2（内置扬声器）复现：输出与输入开流
各等待约 25 秒；每 5 秒重复 `Service media.aaudio didn't start. Returning NULL`，
最终返回 `AAUDIO_ERROR_NO_SERVICE` 后才回退传统 AudioTrack / AudioRecord。
`service list` 没有 `media.aaudio`，`aaudio.*` 系统属性未设置。

原因是“允许 MMAP”原来强制设置进程策略 AUTO，绕过了旧平台缺省 NEVER 的策略。
现在开启表示保留原始平台策略（包含 UNSPECIFIED=0，由平台解析属性及默认值），
关闭仍临时设置 NEVER；两端不同开关时在开流前恢复各自策略，完成或失败后恢复
进程原值。无需查询隐藏 Binder 服务，也不对无法取消的原生开流创建超时逃逸线程。

原版日志还确认自动输出选择 48000 Hz、USB 输入选择 44100 Hz，随后一致性校验失败。
输入采样率/格式设为自动时，现在请求已打开输出流的实际采样率/格式，由 Android
音频后端处理设备兼容；用户显式参数仍优先，并保留开流后的严格校验及原文错误提示。

参考 Android 9 AOSP：
- https://android.googlesource.com/platform/frameworks/av/+/android-9.0.0_r1/media/libaaudio/src/core/AudioStreamBuilder.cpp
- https://android.googlesource.com/platform/frameworks/av/+/android-9.0.0_r1/media/libaaudio/src/binding/AAudioBinderClient.cpp

新增 MMapPolicyTest 在手机虚拟机上模拟平台 NEVER，验证允许开关不强制启用 MMAP、
两端混合开关及失败释放后策略恢复，并覆盖 UNSPECIFIED 策略恢复。
RelayProgressTest 增加 44100 Hz / I16 输出与全自动输入的协商测试；现有三组原生测试
配置补齐当前 JNI 所需的积压阈值和两端 MMAP 字段。

验证：四 ABI debug/release 和测试 APK 编译成功，release v2 签名校验通过。
手机虚拟机首轮 16 项仪器测试、横屏参数回归 5 项全部通过；release 横竖屏截图
已检查，最后安装 5.2 release 并恢复竖屏默认输入设备页，虚拟机保持运行。
完整 lintRelease 检查发现基线已有 4 项 NewApi 错误（values / values-night 中的
API 29 系统栏对比度属性）及 9 项警告；这些资源在本次提交中未改动。
电视仅用于原版复现和日志诊断，修复版按要求仅在手机虚拟机测试，未验证电视实际听音。

## v5.3 系统栏主题 API 版本修复

将 API 29 才提供的 enforceStatusBarContrast / enforceNavigationBarContrast 移到
values-v29/styles.xml，基础 Theme.HighSpeedIEM 继承 Base.HighSpeedIEM。
浅色、深色的基础主题保留原窗口背景、透明系统栏和图标明暗；API 27/28 使用基础
主题，API 29+ 追加关闭对比度遮罩的属性。没有屏蔽 Lint，也未提高最低 API 版本。

验证：四 ABI debug/release 编译通过，完整 lintRelease 为 0 errors / 9 warnings，
此前四项 NewApi 错误全部消除。手机虚拟机 debug 浅色/深色各横竖屏截图检查通过；
签名 release（v5.3 / versionCode 58）校验并安装成功，最后恢复浅色竖屏默认首页。

# 3.2 转发延迟优化

日期：2026-10-11。基线：3.1 / `f8d2fc2`。版本：3.2 / versionCode 23。

## 实现

单输出仍由输出回调直接非阻塞读取输入，不增加应用 FIFO、重采样、音效或额外线程。默认优先低延迟，接受短暂缺音，但不等待 UI 来调整 PCM 缓冲。

- 输入从 **0 帧额外余量**开始，包括启动时也转发已经读到的部分帧，仅为缺少的尾部补零。第一次输入尚未产生数据时不增加余量。
- 短读后按较大输入/输出 burst 的 **1/4** 增加余量；余量上限是四个 burst 与 `capacity - callbackFrames` 的较小值，容量小于回调时仍允许部分读取。容量只是上限，不主动填满。
- 已连续成功读取至少两秒时遇到单次卡顿，先按原余量恢复；只有重复短读才继续增加余量，避免一次卡顿造成长期额外延迟。
- 连续无缺帧 10 秒后尝试减少 1/4 burst，允许降到 0。失败恢复原值，重试间隔增加到 30/60/120 秒；降低后的观察窗口为 10 秒。启动、重新填充及失败试验的所有补零帧均保留在统计中。
- 输入积压按当前余量有界丢弃，避免卡顿造成长期播放旧声音。单输出读取不阻塞。跨输出仍保留各自 SPSC 队列，各消费者独立修剪；去除原来修剪目标额外保留的一个输入 burst，并使用同样的细粒度恢复策略。
- 转发输出从 **1 burst** 尝试；使用锁定的 Oboe 1.11.0 `LatencyTuner` 的 minimum/reset 接口，使一个 burst 不被其构造函数重置为两个 burst。铃声及单独设备探测继续使用两个 burst。
- 输出 xrun 后仍由同一个 LatencyTuner 增大缓冲。无 xrun、无输入缺帧 15 秒后每次仅尝试减少一个 burst；失败恢复并延长重试间隔，避免每 30 秒直接跳回最低值。`buffer` 报实际设备返回值，`outputTarget` 是 tuner 的请求下限，二者可能不同。

请求 LowLatency、自然采样率、VoicePerformance/VoiceRecognition 兼容逻辑和严格手动格式/预设/共享模式继续生效。没有猜测厂商私有参数、引入第三方 RTC SDK、操作 HAL 或强制系统通信模式。

## 对照入口

`NativeAudio.startConfigured(routes, latencyMode)`：

| 参数 | 输入策略 | 转发输出起点 |
| --- | --- | --- |
| 0 | 3.1 原始策略，至少一个 burst | 两个 burst |
| 1 | 零起步、四分之一 burst 自适应 | 两个 burst |
| 2（app 默认） | 零起步、四分之一 burst 自适应 | 一个 burst |

参数在开流时确定，运行中不切换。原 UI、会话操作锁和串行开停保持一致；修改设备参数仍须先停止。此对照入口供诊断使用，不增加新的生产 UI。

## 验证与限制

测试仅使用持续运行的手机模拟器 `Medium_Phone_API_37.0` / `emulator-5554`，API 37、x86_64。实际 CompactFlow 在手机主线程临时加载，finally 恢复手机界面。没有测试电视或手表模拟器，也没有物理电视连接。

基线单输入/单输出实际模式为 Shared / None，输入 burst=768、输出 burst=960。旧版自动格式连续 35 秒，预热两秒后的 `gap=0/1586304`，输出 `buffer=1920`，最终输入 `queued=1243`、`cushion=2`。第一次冷启动设备探测未完成、出现旧版 ErrorInternal，重开 Activity 后同一基线通过；失败日志保留。

最终音频对照结果（同一路由，48000 Hz，Shared / None；均扣除前两秒，但完整累计计数另保留）：

| 策略 / 格式 | 时长 | 平均输入排队帧 | 预热后补零帧 / 输出帧 | 最后 10 秒补零帧 / 输出帧 | 预热后输出 xrun 增量 | 结束输出 buffer |
| --- | --- | --- | --- | --- | --- | --- |
| 0 原始 / 自动 | 35s | 1054.8 | 0 / 1588480 | 0 / 475008 | 0 | 1920 |
| 1 仅输入 / 自动 | 35s | 616.0 | 1943 / 1587392（0.122%） | 982 / 474368 | 0 | 1920 |
| 2 默认 / 自动 | 65s | 650.3 | 4849 / 3030720（0.160%） | 961 / 474240 | 3 | 1920 |
| 2 默认 / I16 | 35s | 632.6 | 4234 / 1587584（0.267%） | 980 / 474368 | 0 | 1920 |
| 2 默认 / Float | 35s | 622.4 | 2922 / 1587456（0.184%） | 1824 / 473984 | 3 | 1920 |

默认策略平均输入排队减少约 **38.4%**，仅改输入减少约 **41.6%**。新策略最终 guard=1440 帧，旧策略 cushion=2、余量尺度为1920帧。实际输出请求 960 帧曾生效，但此模拟器会欠载并回退到 1920；不能宣称在该模拟器获得稳定单周期输出。默认自动格式完整累计 `gap=13533`、`xrun=9`、输出缓冲试探 1 次失败 1 次；不是零缺帧或零欠载。试探失败后回到 1920，没有之前额外涨至 2880 的重复增长。

功能回归覆盖 native-selftest、audio-latency、forward-background、device-lifecycle、forward-start-stop、error-stop、ringtone-native-drain、selection-preview、compact-binding-phone，最终九个不同场景均取得通过结果。原生自测含零余量、部分读、四分之一 burst 恢复、容量不足、降档失败冷却、波形旧帧修剪、两个线程并发 SPSC、跨输出独立消费和 PCM16/24/32/Float。显式 preset=6 生效，16000/48000 冲突保留原始 ErrorInvalidRate 且全部关闭，非法策略返回原始 ErrorIllegalArgument。

四 ABI Debug/Release 构建与 release vital lint 通过，38 项 JVM 测试无失败/错误。测量工具自测通过；额外生成 8/16/24/32 位、双通道、每种 10 个事件的已知 720 帧延迟 WAV，均正确测得 15ms。这是**人工构造的算法测试值**，不是 app 延迟。

测试过程也保留失败：首次较短 I16/Float 5 秒窗口中，尚在自适应建立余量，补零占比为 1.52% / 0.76%；改为各 35 秒以覆盖恢复、降档和冷却，同时增加独立最后 10 秒检查。第一轮旧恢复逻辑的自动格式稳态占比为 0.67%，随后修正孤立卡顿处理和 tuner 重复增长。没有删除失败窗口或把它们记作通过。

原始证据目录：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\qa\latency-3.2`。`baseline-repeat.txt` 是旧版基线；`first-audio-tests.txt`、`trace-audio-tests.txt`、`final/scenarios.txt` 保留中间结果和失败；最终长时音频验证在 `final-audio/scenarios.txt`、`final-audio/*.csv`、`final-audio/metrics.json`；其余八项回归在 `final/scenarios.txt`。原生实现与 APK 在这两批间相同，仅延长调试场景并增加最后十秒检查。

正式包冷启动的设备探测仍会给模拟器端点 i10、i14、o5 返回 ErrorInternal，`DeviceScan` 明确记录其开流失败；i8、i12、o2 可用。这些失败端点保留原文并默认禁用，未隐藏错误。实际频谱/转发使用可用的 i8→o2；不能把其他端点的探测错误误报为这条转发路线失败，也不能宣称所有模拟音频端点都可用。

签名 Release 在同一手机实际触控完成频谱开启/关闭、配对、启动、后台 8 秒、返回、停止。两端显示 policy=2；前后输入有效帧 730086→1341462、输出帧 738816→1351360，后台继续转发；这段包含界面采集时间的窗口新增补零 1168 帧，输出 xrun 保持 6。R8 后 JNI、统计和绘图正常，无 Monitor 引擎错误、缺失 JNI 绑定或崩溃。停止后无 ServiceRecord、无应用持有的唤醒锁。最后显示一组 i8→o2 就绪配对、转发停止，手机模拟器继续运行。原始界面树、截图、日志和 `summary.json` 位于证据目录的 `release/`。

## 签名交付

APK：`C:\Users\xuyuntao\AndroidBuild\HighSpeedIEM\cn.xiaotao29.HighSpeedIEM_3.2.apk`。

包含 armeabi-v7a、arm64-v8a、x86、x86_64 的 `libhighspeediem.so`，沿用原项目 Android Debug 证书，APK v2 验签通过。证书 SHA-256：`c18975595fb840d090c50a140906ebb6f6ee63af183eaa32c7154ea10a242fbc`。

APK SHA-256：`D8680D99BBFE95D8812630D4498AEDBD328D63FCD4CD2B226BC1D2B724116A5D`。

缓冲帧数换算成毫秒只表示可见的缓冲时长。`appQueue=0`、输出 xrun=0 或模拟器通过均不能证明电视物理端到端延迟，不能直接相加输入 queued 和输出 buffer 来宣称延迟下降。

## 真机复测与波形测量

安装四 ABI 签名 APK，在同一设备、同一话筒、同一输出、同一音效/音量下进行交替 A/B。先全部自动参数，再用既有独占开关及录音预设分别对照。记录实际 performance/sharing mode、burst、callback、capacity、guard、queued、buffer、xrun、gap、dropped；普通共享路线可能仍为 None。建议至少稳态 30 分钟，跨设备时钟观察 60 分钟，同时播放实际伴奏并测试退到后台、返回、停止及拔插。

若设备仍走普通混音路径，应用不能保证消除 USB 无线接收器、电视音效、HDMI/ARC/功放的缓存。可分别比较 PCM、关闭额外音效、直连输出。若普通 APK 到达下限，厂商公开硬件耳返接口或外部直通混音器才是进一步的路线；不根据品牌猜测支持。

`tools/measure_latency.py` 可测外部同一录音时钟的双通道 PCM WAV：通道 1 记录参考输入，通道 2 记录返声输出，使用离散短 chirp/噪声，给出每次参考窗口起点。不要使用不同录音设备的两个时钟。声学录制需记录并校正传播距离；功放扬声器输出必须先安全衰减再接录音输入。

```powershell
python tools/measure_latency.py --self-test
python tools/measure_latency.py capture.wav --events 2,4,6,8,10,12,14,16,18,20 --output latency-result.json
```

依赖 numpy。默认参考窗口 100ms、正延迟搜索 0–250ms；按刺激长度调整 `--window-ms`，按线路调整 `--min-ms/--max-ms`。工具输出归一化相关峰、次峰歧义、有效/无效事件及 median/P95/max；少于 10 个有效事件明确标记不足。搜索边界、低相关和多峰结果不纳入汇总；JSON 同时保留失败事件。算法自测覆盖已知延迟、噪声、增益、相位反转、多峰和静音；它不能替代真实波形录制。没有提供真实录音，因此没有编造毫秒收益。

## 复现手机验证

```powershell
adb -s emulator-5554 install -r C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/app/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm grant cn.xiaotao29.HighSpeedIEM android.permission.RECORD_AUDIO
adb -s emulator-5554 shell pm grant cn.xiaotao29.HighSpeedIEM android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am start -n cn.xiaotao29.HighSpeedIEM/.MainActivity
adb -s emulator-5554 shell am instrument -w -e scenarios native-selftest,audio-latency,forward-background,device-lifecycle,forward-start-stop,error-stop,ringtone-native-drain,selection-preview,compact-binding-phone cn.xiaotao29.HighSpeedIEM/.test.UnifiedTestInstrumentation
```

audio-latency 逐一运行策略 0/1 自动格式各 35 秒、默认策略自动格式 65 秒，以及默认策略 I16/Float 各 35 秒，采集每 200ms 的 CSV，包含预热及所有失败窗口。CSV 位于 app 私有 files 目录，debug 下可用 run-as 读取。gap 的功能验收阈值为预热后的 0.5%，并独立检查最后 10 秒的比例，不能据此推断主观音质或物理延迟。

也可运行 `tools/Collect-Latency.ps1 -Serial emulator-5554` 自动采集场景结果、CSV、AudioFlinger/AudioPolicy 和崩溃日志；脚本要求已安装 debug APK，不启动或关闭模拟器。

## 参考

- 用户提供的 `D:\xuyuntao\Desktop\耳返性能优化\生成低延迟优化方案.pdf` 和同目录 `HighSpeedIEM_LowLatency_Codex_Prompt.md`。
- [Android 低延迟音频](https://developer.android.com/games/sdk/oboe/low-latency-audio)：两个 burst 是稳定起点；本版按用户接受轻微不稳定的要求尝试一个 burst，失败自适应增加。
- [Oboe 全双工指南](https://github.com/google/oboe/wiki/Using-FullDuplexStream-for-Synchronized-IO)：输入 cushion 可为零；本项目的持续修剪/恢复机制与示例启动过程不同。
- 本地锁定版本的 `oboe/src/common/LatencyTuner.cpp`、`oboe/include/oboe/LatencyTuner.h` 以及原始 3.0/3.1 验收记录。

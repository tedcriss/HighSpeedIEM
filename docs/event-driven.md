# 原生事件与有界采样（3.3）

设计参考用户提供的 `HighSpeedIEM_EventDriven_Feasibility.md`，以当前 3.2 源码为基线。

## 事件链路

Oboe 错误回调、非阻塞读取失败和健康看门狗统一调用 `EngineSignal::fail`。
原子比较交换只记录第一个错误；非阻塞 Linux eventfd 唤醒等待线程。
实际 buffer、guard、cushion、输出目标变化使用另一事件标志，连续通知按待处理标志合并。
JNI 等待函数在取得共享 Signal 后释放控制锁，用无限期 poll 等待真实唤醒，没有定时错误查询。
音频回调只比较数值、设置原子标志并在变化时写 eventfd，不调用 JNI、不创建对象、不关闭流。
这是少量内核唤醒开销，并非硬实时无开销保证。

Kotlin 等待线程把带原生 sessionId 的事件送到主线程；串行音频控制线程完成停止与释放。
旧会话事件在接收和异步结果发布时均校验身份。停止先清除当前身份并唤醒等待线程，关闭全部流，
等待线程退出后才完成控制操作。Signal、描述符由共享所有权保活，没有 JNI 全局引用。
每次重开都有新的 sessionId；错误只随错误标志发送一次，运行设置事件不会重复发送错误。

AppOps 使用 `startWatchingMode`，API 29 起包含 `WATCH_FOREGROUND_CHANGES`。
开始录音前、权限申请结果和恢复前台时仍核查权限。监听变化只触发重新核查，
不直接假定录音被禁止。Binder 查询在串行控制线程执行。注册失败显示原文，
并回退到 1 秒健康检查中的权限核查。dispose 时注销监听。
系统录音静音继续使用 `AudioRecordingCallback`。

## 快照与定时职责

| 内容 | 机制 |
| --- | --- |
| 实际开流 rate/format/channels/sharing/performance/burst/capacity/preset/hardware | 每条流读取一次，按 sessionId 缓存；重开重新读取 |
| 实际 buffer/guard/cushion/outputTarget | 实际值变化事件读取，未变快照复用 |
| frames/gap/drop/xrun/queued 与诊断计数 | 首次快照一次；前台任务页可见时每 3 秒采样 |
| 独立频谱预览 | 输入页可见且预览有效时每 150 毫秒采样 |
| 无帧进展 | 每 1 秒读取帧推进；原 2 秒无进展阈值保留 |
| 开流、清理、扫描超时 | 按开始时间安排一次截止通知，不再每秒查询长操作 |
| 设备变化 | 原生 AudioDeviceCallback，200 毫秒合并事件 |

`EndpointSnapshot` 分别保存 config/settings/telemetry、sessionId 和递增 revision。
固定配置复用，设置事件保留原计数快照，计数采样不触发全局 session.version。
UI 继续订阅独立 StateFlow，不解析原生聚合字符串。诊断 `endpointStats` 和 `pollError`
保留供测试对照；生产会话和铃声不使用 `pollError`。

1 秒采样无法精确定位最后一个音频帧的时刻；刚发生卡死时，首次检查可能仍读到
上一次检查之后的帧，因此此看门狗的最坏发现时间约为 4 秒，再加控制线程调度时间。
正常静音仍有帧推进，不按零音量判断卡死。慢驱动占用资源时保持操作锁，不因超时强行重开。

## 缓冲调优的失败事件

InputGuard 与输出 LatencyTuner 仍在原生音频回调中根据实际缺帧/xrun 调整。
一次降低 guard 或输出缓冲后发生短缺，就在当前流内记住回退后的下限，
不再仅凭时间到达重复尝试已失败的值。重新开流时创建新策略状态，重新学习。
稳定期仍可尝试尚未失败的更低值；实际缓冲、guard 或目标变化触发快照事件。
这是连续性优先的取舍：同一次开流中，临时负载造成的失败下限也会保留至重开。

## 铃声与 UI

MediaCodec 改用 Callback；工作线程阻塞等待输入、输出、格式或错误事件，并设置连续
5 秒无事件超时。取消信号唤醒队列，不再反复 dequeue 查询。
AudioTrack 使用原生路由监听、播放位置通知和末帧 marker。写入背压等待播放进度通知，
取消唤醒等待；解码结束仍等待实际播放尾部，最后依次释放输出、解码器、提取器和回调线程。
AAudio 铃声软件队列满时按需申请 eventfd 唤醒，只有等待者存在时音频回调才发写空间通知。
取消唤醒由控制线程执行。AAudio 没有末帧播放 marker，硬件尾部仍保留工作线程 20 毫秒采样，
只在排空阶段运行，并有连续无进展超时。

下拉框聚焦后的长文本滚动继续使用 Compose 的原生 ScrollState/动画；通过 snapshotFlow
订阅框架的 MotionDurationScale 状态，系统禁用动画时取消循环并回到开头，重新启用后由事件重启。
不再每 1.5 秒查询动画是否禁用，不注册第二套系统设置监听器。
设备防抖、动画等待、帧队列背压与硬件尾部采样分别有明确用途，没有把所有循环强行事件化。

## 官方依据

- [Oboe AudioStreamErrorCallback](https://google.github.io/oboe/classoboe_1_1_audio_stream_error_callback.html)
- [Android AppOpsManager](https://developer.android.com/reference/android/app/AppOpsManager)
- [MediaCodec.Callback](https://developer.android.com/reference/android/media/MediaCodec.Callback)
- [AudioTrack 播放位置通知](https://developer.android.com/reference/android/media/AudioTrack#setNotificationMarkerPosition(int))
- [AudioRouting.OnRoutingChangedListener](https://developer.android.com/reference/android/media/AudioRouting.OnRoutingChangedListener)
- [AndroidX WindowRecomposer 动画缩放状态](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/WindowRecomposer.android.kt)

验证记录见项目根目录 `事件驱动验收-3.3.md`。手机模拟器结果不代表物理 USB 热插拔或真机音频延迟。

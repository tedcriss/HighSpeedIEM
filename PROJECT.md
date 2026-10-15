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
    混音计划以 `shared_ptr` 原子发布，回调内不加锁不分配。
  - 开流：输入先开先启动，输出随后；开流后校验两端实际采样率/格式一致（当前不支持
    重采样/格式转换），不一致立即关流报错。`AAudioStreamBuilder_setErrorCallback`
    回调式报错 → JNI → 主线程停止转发 + 清除选中 + Snackbar 原文。
    JNI 的 `onNativeError(String)` 接口和实现用 `@Keep` 保留，避免 release 压缩移除；
    设备移除先记录是否在使用，再清除选中项。开流期间收到停止请求会在开流结束后
    立即关流，完成前保持阻塞；移除无关设备不清除正在使用的混音配置。
  - 空间音频固定 `AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER`（API 32+）；MMAP 策略
    `AAudio_setMMapPolicy(AAUDIO_POLICY_AUTO)`（dlsym 保护，API 29+）。

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

## 设备类型映射

`AudioDeviceTypes` 维护 TYPE_* 数字 ID → 中文名与原始枚举名；详见 `设备类型映射.md`。

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
  - `service/ForwardingService`：前台服务（microphone + mediaPlayback + 部分唤醒锁），
    常驻通知点击打开转发控制 tab；最近任务清除即停止转发并释放资源。
- **后端**（`app/src/main/cpp/`）
  - `RelayEngine`：输出流 `setDataCallback` 回调内 `AAudioStream_read` 拉取输入流
    （OboeTester 式回调驱动转发）。InputMargin 门槛：输入缓冲已有帧数 ≥
    「输出 burst 帧时长按采样率换算的输入帧数（向上取整）+ 输入 burst 帧数」才读取，
    否则输出静音；短读尾部补零。每输出通道按位掩码选择 0..N 个输入通道做逐帧平均混音，
    混音计划以 `shared_ptr` 原子发布，回调内不加锁不分配。
  - 开流：输入先开先启动，输出随后；开流后校验两端实际采样率/格式一致（当前不支持
    重采样/格式转换），不一致立即关流报错。`AAudioStreamBuilder_setErrorCallback`
    回调式报错 → JNI → 主线程停止转发 + 清除选中 + Snackbar 原文。
  - 空间音频固定 `AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER`（API 32+）；MMAP 策略
    `AAudio_setMMapPolicy(AAUDIO_POLICY_AUTO)`（dlsym 保护，API 29+）。

## 构建

- 最低 API 27，目标 API 37.0；AGP 9.4.1（Kotlin 内置，勿再应用 kotlin.android 插件）+
  Kotlin Compose 插件 2.3.21；CMake 4.1.2 / NDK 30.0.16248370。
- 编译输出固定在 `C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/<module>`（根
  `build.gradle.kts` 重定向），源码树保持洁净。
- 发布 APK 含 armeabi-v7a / arm64-v8a / x86 / x86_64 四 ABI，签名 release，
  命名 `cn.xiaotao29.HighSpeedIEM_<版本>.apk`；调试可用 `-Pabi=x86_64` 单 ABI 提速。

## 设备类型映射

`AudioDeviceTypes` 维护 TYPE_* 数字 ID → 中文名与原始枚举名；详见 `设备类型映射.md`。

# Oboe + AAudio 基础转发开发规格

版本：2026-10-11。目标：在 Android 上实现“所选输入设备采集 → 基础 PCM 转发 → 所选输出设备播放”，输入与输出分别指定设备 ID。

本规格仅采用 Native：Oboe + AAudio。两端流明确请求 AAudio；设备选择通过各自的 setDeviceId() 实现，开流后及启动后读取实际 getDeviceId()。选择请求必须得到实际路由核验，不能只记录 UI 选择值。

开发不需要全民 K 歌安装包、腾讯原生库、反编译工程或其中的任何文件。文中的自定义接口由新工程实现；外部音频接口采用公开 Oboe 和 Android API。

本阶段实现基础转发、设备选择、开关、音量与统计。处理接口初始为旁通，预留未来特效扩展。正常停止与释放包含在完整生命周期中，自动错误恢复不属于本次实现范围。

## 1. 平台、依赖与构建

| 项目 | 确定规格 |
|---|---|
| 运行平台 | Android，minSdk 27 |
| native 语言 | C++17，Android NDK，CMake |
| 音频封装 | Google Oboe **1.7.0**，固定版本源码构建 |
| 音频后端 | 两端均显式请求 AudioApi::AAudio，数值 2 |
| ABI | x86_64、arm64-v8a 分别编译；算法和控制代码共用 |
| JNI | 自己的桥接库，例如 monitor_jni |
| 设备选择 | 输入 inputDeviceId、输出 outputDeviceId 独立传入并核验 |
| 功能验证 | Android x86_64 可验证实现；实际路由、音质和延迟需真机测试 |

最小 CMake 关系：

```cmake
cmake_minimum_required(VERSION 3.18)
project(monitor LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
# third_party/oboe 是公开 Oboe 1.7.0 源码。
add_subdirectory(third_party/oboe)
add_library(monitor_jni SHARED monitor_jni.cpp stream_engine.cpp)
target_link_libraries(monitor_jni PRIVATE oboe log android dl)
```

Gradle 的 externalNativeBuild 指向该 CMakeLists；ndk.abiFilters 按目标设为 x86_64 或 arm64-v8a。构建及开流配置保持 AAudio 单一路径，准备成功后确认两端 getAudioApi()==AudioApi::AAudio。

## 2. 应用外壳、设备枚举与路由核验

Manifest 至少声明：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

RECORD_AUDIO 必须取得运行时授权。基线在可见前台 Activity 中工作。

通过 Context.getSystemService(Context.AUDIO_SERVICE) 得到 AudioManager。正常媒体监听采用 MODE_NORMAL，输入预置为 Generic，输出 Usage::Media、ContentType::Music。应用不自动改变系统媒体音量；系统媒体音量与本规格的麦克风增益是两个控制量。

### 2.1 分别枚举输入和输出

```java
AudioManager manager =
    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
AudioDeviceInfo[] inputs =
    manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
AudioDeviceInfo[] outputs =
    manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
// UI 分别展示两组设备；每个选择项保存该 AudioDeviceInfo.getId()。
```

输入从 inputs 中选，输出从 outputs 中选。可用 getProductName()、getType() 展示名称及类型，以 getId() 作为当前设备 ID；isSource()/isSink() 可辅助检查方向。同一个物理设备的输入与输出 ID 不必相同，不把一个方向的 ID 复制给另一个方向。

设备 ID 不能跨拔插、重启等事件假定长期稳定。选择前枚举当前设备；每次开流保存本次选择。设备支持的采样率、声道和格式可以通过 AudioDeviceInfo 查询作为准备参考，实际流参数仍在开流后核验。

### 2.2 请求并验证实际设备

1. 输入 builder 调用 setDeviceId(inputDeviceId)，输出 builder 调用 setDeviceId(outputDeviceId)。
2. 开流后分别读取 getDeviceId()，记录 requestedInputId/actualInputId、requestedOutputId/actualOutputId。
3. 启动两条流后，在开启监听前再次核验两端实际设备 ID。
4. 用户明确指定某一设备时，本次对应请求 ID 为非零值；实际 ID 必须匹配。若实际 ID 为 0、不可确认或不匹配，本次不能报告该设备选择成功，也不能静默播放到其他设备。
5. UI 状态同时展示用户选择和实际路由；只有路由核验通过才把监听开关设为 true。

值 0 表示系统自动选择，仅用于 UI 明确提供“系统默认”选项时；它不等于某个固定麦克风或扬声器。本次需要明确指定的输入/输出应传入实际选择项的 ID。

AAudio 的 setDeviceId() 是路由请求，系统不保证任意输入/输出组合可用。耳机、USB、内置麦克风或扬声器是否能组成指定链路，通过实际两端开流、路由核验与声音测试判断；不能仅因设备出现在列表就宣告成功。

### 2.3 切换设备与设备变化

运行中切换设备采用完整重开流程：监听关闭 → 正常停流 → 关闭两端流 → 更新输入/输出选择及 ID → 按第 5–6 节重新准备、启动和核验 → 监听开启。重新准备应用当前音量及处理器配置。只改变一端选择也要为这条双工链执行该完整流程。

使用 AudioManager.registerAudioDeviceCallback(callback, handler) 监听设备增删，callback 实现 onAudioDevicesAdded/onAudioDevicesRemoved。收到变化后 post 到控制线程刷新列表，并重新核验已选设备。准备释放时调用 unregisterAudioDeviceCallback(callback)。设备变化处理不发生在 PCM 回调线程。

选定设备移除时将该选择标为不可用，关闭监听并执行正常停止；重新选择后再准备。自动替换设备不属于本次流程。DeviceCallback 表示设备清单变化，并不代替流的实际路由核验。

### 2.4 可选音频焦点

基线可不申请音频焦点；它不是开流前置条件。若产品需要处理与其他媒体的竞争，使用 AudioFocusRequest.Builder(AUDIOFOCUS_GAIN)，AudioAttributes 设 USAGE_MEDIA、CONTENT_TYPE_MUSIC，设置监听器后调用 requestAudioFocus(request)，正常停止调用 abandonAudioFocusRequest(request)。该产品策略与 PCM 转发算法独立。

## 3. 单一路径框架与正常控制流程

模块分为 Controller、StreamEngine、DuplexCallback、RealtimeProcessor、可选 RecorderTap、Metrics。Controller 串行管理设备选择、准备、启动、切换与释放；StreamEngine 持有唯一一对 AAudio 输入/输出流。

上层控制接口由本工程实现：

```text
listDevices() -> 分别返回当前输入与输出设备
selectDevices(inputDeviceId, outputDeviceId) -> 保存选择，运行中执行停流重开
prepare(config) -> 打开两端流、检查实际参数、预分配处理对象
start() -> 启动两端流，核验实际路由
setEnabled(boolean) -> 更新监听开关
setVolume(float v) -> 有限数，clamp 到 [0,1]
getStatus() -> 选择值、实际路由、实际配置与运行统计
stop() -> 正常停止
release() -> 正常释放
```

正常状态为 Closed → Prepared → Running。setEnabled 仅控制 Running 状态下的监听输出，不创建新流。prepare 和 start 的路由校验失败时不进入可监听状态。

完整流程：

1. 权限已授予，枚举设备并分别选择输入/输出。
2. 调用 prepare，设置初始 volume/scale。
3. 调用 start；输入与输出实际后端都为 AAudio，格式满足本基线，实际路由满足选择。
4. 路由核验通过后 setEnabled(true)。
5. 运行中改变开关或音量，读取实际状态和统计。
6. 设备切换按第 2.3 节正常重开。
7. 退出时关闭监听，停止两端流，释放资源。

监听关闭但流仍在运行时，输出静音，输入旁路仍可采集。监听开关与 stop/release 分开；全部 PCM 采集与播放由同一套 Oboe + AAudio 双工实现完成。

## 4. Native 对外控制 API

以下 JNI 契约属于新工程，并非需要外部实现的腾讯端点：

```java
static native long nativeOpen(
    int audioApi, int sampleRate, int channelCount,
    int sharingMode, int inputPerformanceMode, int inputPreset,
    int inputDeviceId, int outputDeviceId,
    int mmapPolicy, int initialXRunCount);
static native int nativeStart(long handle);
static native void nativeSetEnabled(long handle, boolean enabled);
static native void nativeSetVolume(long handle, float volume);
static native void nativeSetVolumeScale(long handle, float scale);
static native String nativeGetStatsJson(long handle);
static native int nativeStop(long handle);
static native void nativeClose(long handle);
```

nativeOpen 成功返回正的唯一句柄，失败返回 0；错误原因另存控制线程状态。nativeStart/Stop 成功返回 0，失败返回底层 Result 的负值或本工程明确命名的校验错误。void setter 只更新原子控制值，不开流。Close 后句柄不再使用。一个句柄持有两条流和回调对象，生命期必须覆盖最后一次回调。

| 参数/控制值 | 基线值与范围 |
|---|---|
| audioApi | 固定 AAudio=2；其他值不属于此接口的有效配置 |
| sampleRate | 48000 Hz |
| channelCount | 1；本次只支持单声道 |
| sharingMode | Exclusive=0；Shared=1 可作对照 |
| inputPerformanceMode | 固定 LowLatency=12 |
| inputPreset | 固定 Generic=1 |
| inputDeviceId、outputDeviceId | 各自方向选择项的真实 ID；0 仅用于明确选择系统默认 |
| mmapPolicy | 基础可用档 System=0；禁用 MMAP 对照 Never=1、允许 MMAP 对照 Auto=2；不是 Oboe builder 参数 |
| initialXRunCount | 兼容调节档 20；对照档可以设 0 |
| enabled | 初始 false |
| volume | 有限数，clamp 到 [0,1]；默认 0.5 |
| volumeScale | 有限数，[1,25]；基础单位增益档取 1 |

nativeOpen 完成开流、关联回调、预分配与处理器 prepare，不启动输出；nativeStart 完成第 6 节启动顺序，并在返回成功前核验两端实际路由。上层依次 Open → SetVolume/Scale → Start → SetEnabled(true)。这是完整且确定的新工程生命周期。

JNI 不在音频回调调用 Java。统计 JSON 在控制线程生成；音频回调只写预分配的统计结构/原子计数器。Java setter 拒绝 NaN 和无穷数。句柄表、字符串分配等非实时工作留在控制线程。

完整基础调用实例：nativeOpen(2,48000,1,0,12,1,selectedInputId,selectedOutputId,0,20) → nativeSetVolume(handle,0.5f) → nativeSetVolumeScale(handle,1.0f) → nativeStart(handle) → nativeSetEnabled(handle,true)。停止为 nativeSetEnabled(false) → nativeStop → nativeClose。每个启动调用都检查前一步成功；不把 0 句柄传入后续接口。

## 5. Native 两端 builder 的完整设置

头文件为公开 Oboe 的 oboe/Oboe.h。输入使用 AudioStreamBuilder；openStream(std::shared_ptr<AudioStream>&)。

| 输入设置 | Oboe API 与参数 |
|---|---|
| 方向 | setDirection(Direction::Input) |
| 后端 | setAudioApi(AudioApi::AAudio) |
| 采样率 | setSampleRate(48000) |
| 声道 | setChannelCount(1) |
| 格式 | setFormat(AudioFormat::I16)；数值 1 |
| 共享 | setSharingMode(SharingMode::Exclusive) |
| 性能 | setPerformanceMode(PerformanceMode::LowLatency) |
| 输入预置 | setInputPreset(InputPreset::Generic) |
| 设备 | setDeviceId(inputDeviceId) |
| 会话 | setSessionId(SessionId::None)，数值 -1 |
| 声道转换 | setChannelConversionAllowed(false) |
| 格式转换 | setFormatConversionAllowed(false) |
| 采样率转换 | setSampleRateConversionQuality(SampleRateConversionQuality::None)，数值 0 |
| 数据回调 | 不设置输入 data callback；输出回调负责 read |
| framesPerCallback | 不设置；不能写死每次 480 帧 |
| bufferCapacity | 不设置，后端协商 |

输入打开后确认 getAudioApi()==AudioApi::AAudio，并保存实际格式与设备 ID。明确请求的 rate/channels/format 必须为 48000/1/I16，否则本基线校验失败，不能偷偷换格式。Exclusive、LowLatency 属于请求，系统可能降级，必须记录实际值。

调用 input->setBufferSizeInFrames(2 * input->getFramesPerBurst())，保存 ResultWithValue 返回的实际值。这个值是流缓冲大小；预分配的应用内存容量不是强制等待的音频队列。

输出使用另一个 builder：

| 输出设置 | Oboe API 与参数 |
|---|---|
| 方向 | setDirection(Direction::Output) |
| 后端 | setAudioApi(AudioApi::AAudio) |
| rate/channels/format | setSampleRate/input实际值；setChannelCount/input实际值；setFormat/input实际值 |
| 共享 | setSharingMode(SharingMode::Exclusive)，或传入对照值 |
| 性能 | setPerformanceMode(PerformanceMode::LowLatency)，数值 12 |
| 设备 | setDeviceId(outputDeviceId)，独立于输入；0 仅表示系统默认 |
| 会话 | setSessionId(SessionId::None) |
| 用途/内容 | setUsage(Usage::Media)，数值 1；setContentType(ContentType::Music)，数值 2 |
| 声道/格式转换 | setChannelConversionAllowed(true)；setFormatConversionAllowed(true) |
| 采样率转换 | setSampleRateConversionQuality(SampleRateConversionQuality::None) |
| 回调 | setDataCallback(&duplexCallback)，对象实现 AudioStreamDataCallback |
| framesPerCallback、capacity | 均不主动设置 |

输出打开后确认 getAudioApi()==AudioApi::AAudio，实际 rate/channels/format 必须与输入一致，设备 ID 按第 2.2 节核验。本基线不是另开双声道播放流，也不在监听链加入 48 kHz → 44.1 kHz 重采样。

输出缓冲保留锁定版本 Oboe 的开流默认行为，读取实际 getBufferSizeInFrames()。不额外套用“应用把两端都固定为两个 burst”的假定；Oboe 本身可能调整输出缓冲。此后仅按第 8 节欠载规则增大。

## 6. Native 正常启动、关闭和释放

准备阶段：

1. 打开输入流，执行输入缓冲设置。
2. 创建 duplexCallback，enabled=false、volume=0.5、scale=1、lastXRun=配置值。
3. 回调持有稳定的输入引用；打开输出并检查两端格式。
4. 预分配可选旁路缓冲，调用处理器 prepare。准备阶段没有 callback 数据处理。
5. 保存两端实际参数，返回句柄。

启动阶段：

1. output->requestStart()。
2. input->start(2000000000LL)，超时参数单位为纳秒；等待启动成功。
3. 在控制线程按第 2.2 节核验两端实际设备 ID；通过后 nativeStart 返回成功。
4. 上层 nativeSetEnabled(true)。

输出先启动时，输入尚未 Started 的回调必须输出静音。音频线程不等待输入启动。将初始化和旁路分配全部放在输出启动之前，是本规格的确定实现顺序。

关闭监听：只把 enabled 置 false；两条流可继续工作，输入旁路仍可采集。再次打开只把 enabled 置 true。

正常停止：enabled=false → output->stop(2000000000LL) → input->stop(2000000000LL)；成功停止后确认不再使用处理对象。正常释放：output->close() → input->close() → 销毁回调、处理器、旁路和句柄。控制线程完成这些操作；不能从 onAudioReady 中 close 自己的流。

若 stop 返回失败，本规格不自动恢复，也不能继续释放可能仍被回调使用的对象。返回失败并保留有效生命期即可；正常成功流程已完整给出。

## 7. MMAP 控制及真实查询

没有 AudioStreamBuilder::setMMap(true) 这样的常规接口。AAudio 的进程级策略需要独立控制。

通过 dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL)、dlsym 查找：

```cpp
using SetPolicy = int32_t (*)(int32_t);             // AAudio_setMMapPolicy
using GetPolicy = int32_t (*)();                   // AAudio_getMMapPolicy
using IsMMapUsed = bool (*)(AAudioStream*);          // AAudioStream_isMMapUsed
```

底层策略值：Unspecified=0、Never=1、Auto=2、Always=3。本规格只采用 System/Never/Auto；System 表示完全不修改进程策略。

正常调用流程：

1. 在 AAudio 开流控制阶段查找扩展；三个符号和策略调用结果分别记录。
2. 控制线程串行处理所有需要修改策略的开流操作。
3. getPolicy() 保存旧值。
4. Never/Auto 调用 setPolicy(1/2)，成功后执行实际 openStream。
5. 开流完成后 setPolicy(oldValue) 恢复；输入与输出分别执行同样保护。
6. 每条实际 AAudio 流以 getUnderlyingStream() 取得底层指针；先确认非空且确为 AAudioStream*，再调用 isMMapUsed；空指针记为 Unknown。这个底层入口只用于验证，不参与转发。

若实际流外面包有格式转换封装，应确认 getUnderlyingStream() 传出的是 AAudio 原始流；不能把封装对象地址转成 AAudioStream*。本基线两端格式匹配，不需要应用自行建立转换封装。

Never 档对应禁用 MMAP 的兼容测试；Auto 档允许系统选择。扩展不可用时返回 policyControl=Unavailable；可以显式切换 System 完成功能测试，但不得报告已落实 Never/Auto。实际查询不可用记为 Unknown。

这三项在较旧 Android 上属于可能存在的测试扩展；当前 AOSP 头文件将它们标记为 API 36 引入的公开声明。为兼容较旧设备使用动态符号检测，不能仅凭 compileSdk/NDK 版本断言运行时可用。

## 8. Native 回调、音量与欠载

当前处理链：

输入非阻塞读 → 可选原始 PCM 旁路 → 开关/阈值 → BypassProcessor → 增益/硬裁剪 → 输出。

I16 数据契约：frames 是时间帧；samples=frames*channels；bytes=frames*channels*sizeof(int16_t)。本基线 channels=1。输入输出缓冲均为交错 PCM。read 返回 frame 数，不能把它当字节或再次乘声道当 frame。

以下是直接实现的核心逻辑；tap、processor、atomicMetrics 是本工程模块：

```cpp
oboe::DataCallbackResult onAudioReady(
    oboe::AudioStream* out, void* audioData, int32_t n) override {
    auto* pcm = static_cast<int16_t*>(audioData);
    const int32_t ch = out->getChannelCount();
    std::memset(pcm, 0, size_t(n) * ch * sizeof(int16_t));

    if (input->getState() == oboe::StreamState::Started) {
        auto result = input->read(pcm, n, 0); // timeoutNanos = 0
        if (!result) {
            atomicMetrics.lastReadError.store(int32_t(result.error()));
            return oboe::DataCallbackResult::Stop;
        }
        const int32_t got = result.value();
        atomicMetrics.addRead(n, got);
        if (got > 0) {
            tap.tryCopy(pcm, got, ch); // 可为空实现；复制处理前数据
            const bool on = enabled.load(std::memory_order_relaxed);
            const float v = volume.load(std::memory_order_relaxed);
            const float s = scale.load(std::memory_order_relaxed);
            if (!on || v < 0.1f) {
                std::memset(pcm, 0, size_t(n) * ch * sizeof(int16_t));
            } else {
                // 分块最多 maxBlockFrames，不等待凑足块长
                processor.processChunksInPlace(pcm, got, ch);
                const float gain = 2.0f * v * s;
                for (int32_t i = 0; i < got * ch; ++i) {
                    float x = float(pcm[i]) * gain;
                    x = std::clamp(x, -32768.0f, 32767.0f);
                    pcm[i] = static_cast<int16_t>(x); // 向零截断
                }
            }
        }
    }

    auto xr = out->getXRunCount();
    const int32_t size = out->getBufferSizeInFrames();
    const int32_t cap = out->getBufferCapacityInFrames();
    if (xr && xr.value() > lastXRun && size < cap) {
        lastXRun = xr.value();
        const int32_t wanted = std::min(cap, size + out->getFramesPerBurst());
        auto actual = out->setBufferSizeInFrames(wanted);
        atomicMetrics.recordBufferResult(actual);
    }
    return oboe::DataCallbackResult::Continue;
}
```

输入未 Started、零读或短读的尾部保持静音。只处理 got 帧；不等待补齐 n，不加入 Java AudioTrack.write，也不另开轮询播放线程。基础路径没有应用级中转 FIFO、固定 10 ms 处理帧或额外排队目标。

音量规格：

| 档位 | 参数 | 有效行为 |
|---|---|---|
| 基础单位增益 | volume=0.5、scale=1 | gain=1；无特效时有效读出样本不改变 |
| 基础音量调整 | scale=1、volume 在 [0,1] | gain=2*volume；volume<0.1 静音 |
| 兼容增益对照 | scale=15、volume=0.5 | gain=15，采用同一硬裁剪公式；不是本次基础默认 |
| 开关关闭 | enabled=false | 输出全零；输入仍可采集 |

scale=15 是兼容增益档，不代表所有机型或线上配置的实际值。基础开发不需要猜测线上 scale。阈值比较是严格小于 0.1，volume=0.1 不因阈值而静音。没有额外 limiter、软饱和或自动增益。

欠载规格：lastXRun 初值 20；当输出计数超过保存值且缓冲未达容量，保存新计数，尝试增大一个输出 burst。保存框架实际返回值；只增不减。初值 0 是对照配置。不支持 XRun 查询的后端跳过此步骤，记录不可查询。

回调只执行有界 PCM 运算和预分配数据写入；不分配/释放内存、不等待锁、不访问文件/网络、不构造 JSON、不调用 Java。开关、volume、scale 使用无阻塞原子读取。长时间输入/输出时钟漂移不通过隐藏队列掩盖，观察积压后再单独增加明确的补偿模块。

## 9. 可更改处理接口与可选旁路

处理接口由工程实现：

```cpp
struct PcmFormat {
    int32_t sampleRate;   // 48000
    int32_t channels;     // 1
    oboe::AudioFormat format; // I16
};
class RealtimeProcessor {
public:
    virtual ~RealtimeProcessor() = default;
    virtual bool prepare(PcmFormat format, int32_t maxBlockFrames) = 0;
    virtual void processInPlace(int16_t* pcm, int32_t frames) noexcept = 0;
    virtual int32_t latencyFrames() const noexcept = 0;
    virtual void reset() noexcept = 0;
};
```

初版 BypassProcessor 的 prepare 成功，processInPlace 不改变数据，latencyFrames=0。prepare、reset 和销毁在流停止时调用；prepare 预分配所有处理内存。maxBlockFrames 基线取 4096；回调更大时原地拆为不超过 4096 的连续块，不缓存等待填满。4096 是单次处理上限，不是播放队列长度。

处理器替换流程：关闭监听 → 正常停流 → 构造/prepare 新处理器 → 替换持有对象 → 重启 → 打开监听。第一版不需要实现运行中无缝切换。参数调整可以以后通过固定大小命令邮箱在块边界应用。

未来增加 float DSP 时，由新处理器在预分配空间中完成 I16↔float 转换，不能未经说明改变底层流格式。需要固定帧长、lookahead 或历史缓存的算法自行报告 latencyFrames；基础旁通处理器没有这些延迟。

RecorderTap 默认禁用。若启用，使用预分配 SPSC 槽位，tryCopy 满时丢弃并计数；数据块带 frames、channels、format、采集序号。消费者持有自己的槽位，不能引用下一回调覆盖的输出指针。旁路在效果与音量前，不阻塞监听。实现选用 8 个槽，每槽最多 4096 帧，较大回调分块复制；这是可改的旁路容量，不是监听 FIFO。

## 10. 统计、验证及开发完成标准

每次准备保存两端真实值：

getAudioApi()、getSampleRate()、getChannelCount()、getFormat()、getSharingMode()、getPerformanceMode()、getInputPreset()（输入）、getDeviceId()、getSessionId()、getUsage()/getContentType()（输出）、getFramesPerBurst()、getBufferCapacityInFrames()、getBufferSizeInFrames()。

路由统计分别保存 requestedInputId/actualInputId、requestedOutputId/actualOutputId、核验时间和匹配状态。实际后端必须是 AAudio；实际设备是本方案的验收条件，不能以用户选择值代替实际值。

运行保存：输出 XRun 或 Unsupported、每次 requestedFrames/gotFrames 的统计、短读/零读数、buffer size 变化和实际设置结果、enabled/volume/scale、处理器名称及 latencyFrames、MMAP 真实查询值或 Unknown、旁路丢块数、回调耗时采样。不可查询项明确标识，不以 0 冒充实际值。

| 验证项 | 输入与预期 |
|---|---|
| 后端核验 | 输入和输出实际 getAudioApi() 均为 AAudio |
| 输入选择 | 请求 inputDeviceId 与实际输入 getDeviceId() 匹配，确认声音来自选定输入 |
| 输出选择 | 请求 outputDeviceId 与实际输出 getDeviceId() 匹配，确认声音到达选定输出 |
| 独立路由 | 分别改变输入或输出选择，验证另一端仍符合其请求；只验收设备支持的组合 |
| 未满足选择 | 实际 ID 不匹配或无法确认时，不报告选择成功，不打开监听 |
| 设备切换 | 停流重开后应用新 ID，重新核验两端并恢复当前音量及处理配置 |
| 设备移除 | 已选设备标为不可用，关闭监听并正常停止，等待重新选择 |
| 基础旁通 | I16 已知样本、enabled=true、v=0.5、scale=1；有效读出样本逐值相同 |
| 开关/阈值 | enabled=false 或 v<0.1 全零；v=0.1 不触发阈值静音 |
| 增益 | 计算 2*v*scale，向零截断，上下限硬裁剪；含正负样本 |
| 短读 | 请求 256、返回 100；仅前 100 帧有效，后 156 帧全零 |
| 启动窗口 | 输入未 Started 时输出静音，不等待输入 |
| 欠载规则 | lastXRun=20 时 <=20 不增大，>20 增一个 burst；不超过容量，保存实际返回 |
| 旁路 | 得到音量/处理前数据；消费者落后只丢旁路数据，不拖慢监听 |
| 处理器替换 | 停流替换；Bypass 无新增缓存延迟；开启新处理器后报告其延迟 |
| 真机功能 | 选定输入到选定输出，监听开关、音量及路由均生效 |
| 长时间运行 | 至少 30 分钟，观察积压、短读、欠载、缓冲和回调耗时 |
| 端到端延迟 | 外部脉冲或电气回环测量；固定设备、路由和实际配置，报告分布 |

模拟器的音频路径、宿主缓冲和设备时钟不同，可验证功能与生命周期；真机实际延迟单独测量。基础路径没有自动啸叫抑制，所选麦克风与输出设备的声学耦合属于实际测试条件。

## 11. 完整性与设备验证边界

本文自包含给出 Oboe + AAudio 的构建依赖、独立设备选择、输入/输出 API、配置、准备、启动、回调、短读、音量、欠载、开关、正常停止、设备切换、可更改处理接口和验证标准。基础开发可以在不取得全民 K 歌安装包的条件下开始。

仍需目标设备验证：

1. 所选输入/输出组合能否同时打开，实际路由是否与两个请求 ID 分别匹配。
2. 两端是否取得请求的 Exclusive、LowLatency 和 MMAP，以及实际采样率、格式和缓冲。
3. 基础转发的真实延迟、长期输入/输出时钟漂移表现，是否需要另加补偿。

“支持选择设备”表示实现了独立请求及实际核验；不表示系统必定允许所有列出的设备组合。路由不满足时返回明确失败，不静默使用其他设备。这一边界属于 AAudio 与设备系统策略，而不是文档缺少另一套转发步骤。

## 12. 公开依赖与 API 参考

以下是本方案的公开依赖及 API 参考；核心流程和参数已在正文中完整给出。

- [Oboe 1.7.0](https://github.com/google/oboe/tree/1.7.0)
- [Oboe AudioStreamBuilder：setDeviceId](https://google.github.io/oboe/classoboe_1_1_audio_stream_builder.html)
- [Android AudioManager](https://developer.android.com/reference/android/media/AudioManager)
- [Android AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo)
- [Android AudioDeviceCallback](https://developer.android.com/reference/android/media/AudioDeviceCallback)
- [Android 低延迟音频](https://developer.android.com/games/sdk/oboe/low-latency-audio)
- [AAudio / MMAP](https://source.android.com/docs/core/audio/aaudio)
- [AOSP AAudio 公开头文件](https://android.googlesource.com/platform/frameworks/av/+/master/media/libaaudio/include/aaudio/AAudio.h)

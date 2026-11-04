# 实验：对虚拟屏做 captureDisplay（2026-11-02）

**结论：可行。** 虚拟屏的内容可以用 `SurfaceControl.captureDisplay` 主动抓，实测 **29fps**，
且内容与主屏完全独立（主屏切到收音机时抓到的仍是 QQ音乐）。

关键在于：**拿不到 DMS 虚拟屏的 display token，但可以自己造一个**——用
`SurfaceControl.createDisplay()` 建 display token，再把它的 layer stack 指到虚拟屏的，
这个「影子 display」合成出来的就是这个虚拟屏的图层。

| 指标 | 结果 |
|---|---|
| 采集帧率 | 29fps（33ms 节拍跑满） |
| 稳定性 | 1556 帧，`captureDisplay` 返回 null **0 次** |
| 单帧 capture 耗时 | 平均 **1ms**，最大 18ms |
| 内容独立性 | 主屏切到收音机后，抓到的仍是虚拟屏上的 QQ音乐 |
| 必需条件 | 影子 display 必须挂输出面（ImageReader），否则恒 null |

---

## 一、先踩的坑：token 拿不到

DMS 建的虚拟屏，display token 只存在于 `DisplayManagerService` / SF 内部，**不暴露给客户端**：

| 尝试 | 结果 |
|---|---|
| `VirtualDisplay.mToken` | 是 `IVirtualDisplayCallback`（回调），**不是 display token** |
| `VirtualDisplay` 全部字段 | 只有 `mDisplay` / `mGlobal` / `mSurface` / `mToken` / `mWindowContext` |
| `DisplayCaptureArgs` 字段 | 只有 `mDisplayToken`，**没有 mDisplayId** |
| `Builder` 全部方法 | `setDisplayToken(IBinder)` / `setSize` / `setSourceCrop` / `setPixelFormat` / `setUid` / `setGetDomiColor` / `setUseIdentityTransform` / `setFrameScale` / `setGrayscale` / `setAllowProtected` / `setCaptureSecureLayers` —— **没有 setDisplayId** |
| `SurfaceControl` 静态方法 | 只有 `getInternalDisplayToken()`、`getPhysicalDisplayToken(long)`，**没有按 displayId 取 token** |
| `IDisplayManager` | 没有 getToken 类方法 |
| `createDisplay()` 直接 capture | 返回 token，但 capture 恒 null（1ms 即返回，display 未激活） |

所以第一次实验（`VdCap5`）拿 `mToken` 去 capture，360 次全 null——不是权限问题，是拿错了对象。

## 二、虚拟屏本身：shell 域就能建，不用 root

`VdCap5` 实测：

```java
// 系统 context 的包名是 "android"（uid 1000），与当前 uid 2000 不匹配
dm = (DisplayManager) sys.getSystemService(...);
dm.createVirtualDisplay("asxcap", 2400, 1200, 160, surface, 0x9);
// -> SecurityException: packageName must match the calling uid

// 改成 com.android.shell 的 package context
Context pkg = sys.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
dm = (DisplayManager) pkg.getSystemService(...);
// -> OK, id=36 state=2(ON)
```

- 不需要 root，不需要 `setuid(1000)`；
- `flags = 0x9`（`PUBLIC | OWN_CONTENT_ONLY`），`state=2` 即 ON；
- **`layerStack == displayId`**，这是后面能共享的前提（`dumpsys display` 可查）。

## 三、窗口搬家：这次成功了（推翻上轮结论）

```bash
am display move-stack 4151 39      # 4151 = QQ音乐 的 RootTask id，39 = 虚拟屏 id
```

结果：`RootTask id=4151 ... displayId=39`，虚拟屏出帧从 **0 涨到 11.5fps**（damage 驱动）。

**上一轮"搬不动"的结论是错的**（当时可能 task id 取错或 app 未在前台）。这次一次成功。

副作用（要知道）：搬回主屏后窗口模式变成 `fullscreen`，`am task resize` 无效，
需 `am start -n <pkg>/<act> --windowingMode 5` 才能恢复 freeform 的右栏 bounds。

## 四、造影子 display：完整可用代码

```java
// 1) 建 token（不需要任何权限上下文）
IBinder tok = (IBinder) SurfaceControl.createDisplay("asxraw", false);

// 2) 必须挂一个输出面，否则 capture 恒 null
ImageReader ir = ImageReader.newInstance(2400, 1200, PixelFormat.RGBA_8888, 3);
// 要开线程持续 acquireLatestImage()，否则 BufferQueue 占满会卡住合成

// 3) 用显式 Transaction 激活（静态 setDisplaySurface 在 app_process 里会 NPE：
//    SurfaceControl 的全局 transaction 未初始化）
SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
tx.setDisplaySurface(tok, ir.getSurface());
tx.setDisplayProjection(tok, 0, new Rect(0,0,2400,1200), new Rect(0,0,2400,1200));
tx.setDisplayLayerStack(tok, vdDisplayId);   // ← 关键：指向虚拟屏的 layer stack
tx.apply();

// 4) 正常 capture
DisplayCaptureArgs args = new DisplayCaptureArgs.Builder(tok)
        .setSourceCrop(new Rect(960, 345, 2400, 771))
        .setSize(1920, 568)
        .build();
Bitmap bmp = SurfaceControl.captureDisplay(args).asBitmap();
```

要点：

- **`tx.apply()` 之前 capture 一定 null**（display 没激活）；
- **不挂 surface 也一定 null**（实测 360 次全 null）；
- 静态 `setDisplaySurface()` 会抛 `NullPointerException`（`GlobalTransactionWrapper` 为 null），必须用 `new Transaction()`；
- `setDisplayLayerStack` 用 `displayId` 即可（本车机 `layerStack == displayId`，别的固件要 `dumpsys display` 确认）；
- 影子 display 只是"读"用的视图，窗口归属仍在虚拟屏上，不影响 app 状态。

## 五、实测数据

```
VDT3| tx.setDisplaySurface ok
VDT3| tx.setDisplayProjection ok
VDT3| tx.setDisplayLayerStack(39) ok
VDT3| tx.apply ok
VDT3| frame#1 1920x568 nonBlack=53%
VDT3| t=1s cap/s=23 ok=23 null=0
VDT3| t=2s cap/s=29 ok=52 null=0
...
VDT3| t=54s cap/s=29 ok=1529 null=0 capMs=1(min 1/max 18)
VDT3| SUMMARY ok=1556 null=0 avgCapMs=1 min=1 max=18
```

抓帧内容（`shots/vdraw_600.png`）：QQ音乐 界面，左侧封面、"我的歌声里/曲婉婷"、歌词、播放控制。
该帧的 crop 是 `(0,0,2400,1200)`，所以左侧 40% 是虚拟屏上空白的区域（QQ 窗口在 x≥960）。
生产用 `(960,345,1440,426)` 即可只取窗口横条。

## 六、和现有主屏方案对比

| | 主屏 captureDisplay（当前生产） | 虚拟屏 + 影子 display（本实验） |
|---|---|---|
| 帧率 | 30fps | 29fps |
| 内容来源 | 主屏合成结果 | 虚拟屏图层，**独立** |
| 车机屏切走后 | 跟着变 / 被遮挡就没了 | **继续投原内容** |
| 车机屏能否同时看 QQ | 能 | 不能（窗口已搬到虚拟屏） |
| 额外开销 | 无 | 多一个 2400×1200 输出面在合成 |
| 依赖 | 无 | move-stack 可用 + layerStack 可查 |

## 七、如果要做成正式方案，还差这些

1. **输出面瘦身**：影子 display 尺寸设成 1920×568（投影 2400×1200 缩放过去），
   省掉全屏 2400×1200 的额外合成（现在每帧白算一遍）。
2. **自动搬任务**：从 `am stack list` 解析目标包名的 RootTask id，`am display move-stack`；
   进程 uid 2000 可直接 `ProcessBuilder` 调 `/system/bin/am`。
3. **自动解析 layerStack**：从 `dumpsys display` 里按 displayId 取，别假设 `== displayId`。
4. **交互问题**：窗口搬走后车机屏上看不到 QQ，用户没法在中控上操作。
   要么保留一个镜像预览，要么接受"投屏期间在手机上操作"。
5. **恢复逻辑**：退出时把 task 搬回 display 0 并 `--windowingMode 5` 恢复原 bounds。

## 八、复现命令

```bash
# 1) 建虚拟屏并保持存活（打印 HOLD_VD_ID）
CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin \
  com.probe.asx.VdTok3 hold 2400 1200 300 --child > /data/local/tmp/vdtok3.log 2>&1 &

# 2) 把目标 app 搬上去（RootTask id 用 am stack list 查）
am display move-stack <RootTaskId> <VD_ID>

# 3) 造影子 display 抓帧（参数：layerStack 秒数）
CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin \
  com.probe.asx.VdTok3 raw <VD_ID> 60 --child > /data/local/tmp/vdlong.log 2>&1 &

# 4) 退出：搬回 + 恢复 freeform
am display move-stack <RootTaskId> 0
am start -n <pkg>/<act> --windowingMode 5
```

程序：`asx/src/com/probe/asx/VdTok3.java`（hold / raw 两种模式）、`VdTok2.java`（API 探测）、
`VdCap5.java`（第一版，取错 token 的记录）。

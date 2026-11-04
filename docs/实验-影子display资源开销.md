# 实验：影子 display 的资源开销

日期：2026-11-02
分支：`exp/vd-capture-token`
探针：`asx/src/com/probe/asx/VdShadow.java`（`--main` 为主屏对照，默认走影子 display）
采样脚本：`tools/remote/sample.sh`

## 一、测法

同一台车机（8295，Android 12，8 核，13.4GB 内存），四种配置各跑 16–20 秒，
采样 surfaceflinger 与其自身的 `/proc/<pid>/stat` 增量、`/proc/meminfo`、`/proc/stat`。

`cpuPct = Δticks × 10 × 100 / Δms`（USER_HZ=100），SF 得到的是「占单核百分比」，
整机那列是 8 核累计（除以 8 才是平均每核）。

对照组：
- 纯基线：不建任何 display
- 虚拟屏 + QQ 静止：`VdTok3 hold` 建屏、`move-stack` 搬 QQ，不做 capture
- 主屏 capture：`VdShadow --main --crop 960,345,1440,426`（与当前生产版一致）
- 影子 display capture：`createDisplay` + 共享虚拟屏 layerStack

## 二、结果

| 场景 | SF CPU（占单核） | capture 单帧 | 进程 RSS | 出帧 |
|---|---|---|---|---|
| 纯基线（无 display） | **14–15%** | — | — | — |
| 虚拟屏 + QQ 静止，不 capture | **14.0–14.5%** | — | 124MB | 0 |
| 主屏 capture 30fps（现生产配置） | **20.8–21.8%** | 3ms | 164MB | 27fps |
| 影子 2400×1200 capture | **19.9–20.9%** | 1–2ms | 166MB | 27.5–28fps |
| 影子 1920×568（bufs=1） | **19.4%** | 2ms | 166MB | 27.6fps |

三个关键事实：

1. **虚拟屏静止时零开销**。与纯基线完全一致（14.5%），因为虚拟屏是 damage 驱动，
   画面不变 SF 就不为它合成。之前 2fps 的结论在这里得到再次印证：不是不能合成，是没人催它合成。
2. **影子 capture 比主屏 capture 更便宜**（+5.5% vs +6.5%）。反直觉但合理：
   影子 display 的 layerStack 里只有 QQ音乐 一个窗口，主屏有地图、状态栏、左栏播放器一堆图层。
   capture 单帧也从 3ms 降到 1ms。
3. **整机 CPU 没区别**。830–840（8 核累计，即平均每核约 104%）在四组之间落在噪声内。
   capture 这点开销被 SF 的一个线程吸收，不会抢别的核。

## 三、内存

| 项 | 大小 |
|---|---|
| 虚拟屏输出面（2400×1200 RGBA × 3） | 34.6MB |
| 影子 display 输出面（2400×1200 RGBA × 3） | 34.6MB |
| 影子改为 bufs=1（已验证可用） | 11.5MB |
| 进程 RSS 增量（JVM 堆 + 中转 bitmap） | +42MB |
| 合计最坏 / 最好 | **约 111MB / 88MB** |
| 系统 MemAvailable | **6.2GB** |

RSS 显示的 166MB 里**不含** gralloc 显存，所以显存那部分按几何算：
`w × h × 4 × bufs`。2400×1200 一张 11.52MB。

即便如此，最坏情况也只占可用内存的 1.8%。

## 四、两个必须记下来的坑

### 1. 投影缩放不生效，影子必须与虚拟屏同尺寸

`setDisplayProjection(tok, 0, src=2400×1200, dst=1920×568)` 在这台车上**被忽略**。
建 1920×568 的影子 display，实际行为是 layerStack 的 1:1 左上角裁切：

- 抓到的图左边 960 像素全黑（虚拟屏上 QQ 的窗口从 x=960 开始）
- 垂直方向只到 y=568，QQ 界面的下半截（`为你推荐` 以下）全部丢失

所以影子 display 的尺寸必须 = 虚拟屏尺寸（2400×1200），裁剪与缩放交给
`DisplayCaptureArgs.setSourceCrop/setSize` 做。这条能省掉一堆坐标换算的坑。

### 2. 输出面从不被写，ImageReader 只是「占位」

`outFrames` 在 capture 期间增量恒为 **0**（只有 display 刚激活时出过 1 帧）。
也就是说 capture 返回的像素不经过 `setDisplaySurface` 挂的那个 buffer，SF 直接把
图层渲染进 capture 自己的硬件 buffer。

推论：那个 ImageReader **纯粹是为了让 display 处于「有输出面」的可合成状态**，
所以 `bufs` 可以直接降到 1，省 23MB，实测 27.6fps / 0 null 完全正常。
（`--nosurf` 则不行，capture 恒 null，这一条上一轮已验证。）

## 五、结论

**资源完全够用，而且影子方案比现生产的主屏方案更轻。**

- CPU：SF 多 5.5 个百分点（单核的 5.5%），比主屏 capture 的 6.5% 还低
- 内存：88–111MB，可用 6.2GB
- 采集：capMs 1ms，28fps，0 次 null

生产配置建议：

```
影子 display : 2400x1200（必须与虚拟屏一致）
输出面       : ImageReader 2400x1200 RGBA_8888, maxImages = 1
capture      : setSourceCrop(中间带) + setSize(1920, 568)
```

## 六、复现

```bash
# 1. 建虚拟屏（拿 displayId，日志里的 HOLD_VD_ID）
adb shell "CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin \
  com.probe.asx.VdTok3 hold 2400 1200 900 --child > /data/local/tmp/vdtok3.log 2>&1 &"

# 2. 把目标窗口搬上去
adb shell "am display move-stack <rootTaskId> <displayId>"

# 3. 影子 capture + 采样
adb shell "CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin \
  com.probe.asx.VdShadow --stack <displayId> --sec 20 --size 2400x1200 --out 1920x568 --child"

# 4. 主屏对照
adb shell "CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin \
  com.probe.asx.VdShadow --main --crop 960,345,1440,426 --out 1920x568 --sec 16 --child"
```

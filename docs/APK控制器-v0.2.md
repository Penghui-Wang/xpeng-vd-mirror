# APK 控制器 v0.2（AdbProbe → 控制面）

> **包名已迁移（2026-11-05）**：APK 由 `com.probe.adbprobe` 改为 `com.xiaopeng.vdcast`，
> 应用名改「仪表投屏」，新增 `res/mipmap-*` 图标。本文档保留当时的实测记录，
> 下文出现的旧包名属于历史事实，不再回改；当前构建/部署请以 `README.md` 为准。

一句话：**把 `tools/startcast.sh` / `stopcast.sh` / `status.sh` 的全部动作，连同仪表端接收程序，
一起搬进一个 1.1MB 的 APK**，特权操作一律不自己干，交给 adbd 以 `u:r:shell:s0` 域执行。

## 为什么这样分层

app 进程在 `untrusted_app` 域，拿不到 `CAPTURE_VIDEO_OUTPUT` / `ACCESS_SURFACE_FLINGER` / `READ_FRAME_BUFFER`
（都是 signature 权限），而且 §4.6 已经证明「root 也不行」——是域被 SF 限制。
唯一合法入口是 adbd：它 fork 出的子进程域由 adbd 的域转换规则决定（`adbd → shell`），
**跟连进来的是谁无关**。所以 APK 只需要两件事：能连上 127.0.0.1:5050、能过 AUTH。

## 组成

| 文件 | 职责 |
|---|---|
| `src/.../Adb.java` | 最小 ADB 客户端：CNXN / AUTH(TYPE2 签名, TYPE3 公钥) / OPEN / WRTE / CLSE / `sync:` SEND |
| `src/.../Icrpc.java` | 仪表 30510 管理通道：protobuf 帧 + `(cat 帧; sleep) \| nc`，与 `probe/icrpc.py` 逐字节一致 |
| `src/.../VdCtl.java` | 控制面编排：启动 7 步 / 停止 3 步 / 状态 / 裁切 |
| `src/.../MainActivity.java` | 全代码构建的界面：连接、启动 vd、启动 main、停止、状态、自检、裁切框、日志 |
| `assets/vd.dex` | 由 `asx/build.sh` 产出（72KB），启动前按大小判断是否需要重推 |
| `assets/killvds.sh` | 与 `tools/remote/killvds.sh` 同源，每次覆盖 |
| `assets/icrecv_dbg` | 仪表端接收程序，`tools/icrecv2` 交叉编译的 ARMv7 静态 ELF（2.4MB，压进 APK 后 1.0MB） |
| `test/MockAdbdTest.java` | 本机 mock adbd，14 项协议断言，不接车机 |

## 脚本 → Java 对照

| 原脚本 | 现在 |
|---|---|
| `adb.sh` 的重连重试 | `MainActivity.ensureConn()`：失败即 `close()`，下次调用重连重认证 |
| `adb push` | `Adb.push()`：sync SEND/DATA/DONE，按对端 `CNXN.arg1` 决定分片大小（老 adbd 只有 4096） |
| `adb shell "cmd"` | `Adb.exec()`；需要退出码时 `execRc()` 用 `__RC=$?` 哨兵 |
| `startcast.sh [0..7]` | `VdCtl.start()` |
| `stopcast.sh` | `VdCtl.stop()` |
| `status.sh` | `VdCtl.status()` |
| `probe/icrun.py` | `Icrpc.run()`（输出同样剔除非打印字符） |
| `tools/ideploy.sh` 的 HTTP 中转 | `VdCtl.relayToCluster()`：脚本落盘 + `setsid` 脱离会话 + pid 文件回收 |
| `echo 'x,y,w,h' > vdcrop.txt` | `VdCtl.setCrop()` |

三处照抄了脚本里踩过的坑：

1. 起长驻进程必须 `setsid ... > log 2>&1 &`，让 shell 立刻返回，否则撞 adbd 的流超时。
2. 停进程只能靠 `killvds.sh`（`ps` + `/proc/<pid>/cmdline` 以 `app_process` 开头），
   `pkill -f 类名` 会把执行它的 shell 自己杀掉。
3. 仪表端没有 `pkill`，统一 `ps -o pid,args | awk`，并把匹配串拆成 `icrecv_` + `dbg` 再拼，
   防止命中执行命令的 shell 本身。

## 已验证（离线，`bash adbprobe/test/run.sh`）

14 项断言全过：密钥生成、CNXN 握手、shell 回包、`execRc` 退出码与哨兵剥离、
300KB 文件 push 字节一致、模式 0100644/0100755、Icrpc 帧落盘与 `nc 30510` 接线、输出清洗。

另外三条命令的 30510 帧 hex 与 `probe/icrpc.py` 的输出**完全相同**（含中文 UTF-8）。

构建侧：aapt2 → javac → d8 → 注入 dex → zipalign → apksigner，v2+v3 签名通过，
lambda 已被 d8 脱糖（dex 内 `invoke-custom` 计数为 0），入口 Activity 注册正确。

仪表端二进制侧：ELF32 / ARM（machine=40）/ type=EXEC / 无 INTERP / 无 DYNAMIC（纯静态），
两次构建 md5 相同（`62fda882…`），APK 内 `assets/icrecv_dbg` 与源码产物 md5 一致，
三张卡片图可正常解码（RGBA，592×104 / 310×180 / 310×180）。

## 待真机验证（按顺序）

1. **连接**：点「连接」→ 首次弹窗点「允许 + 一律允许」→ 日志出现 `已连接 127.0.0.1:5050`。
   若三项里只有这一项失败，大概率是 SELinux 不允许 `untrusted_app` 连本机 adbd 端口。
2. **自检**：`id -Z` 必须是 `u:r:shell:s0`；`am stack list` 行数 > 3；`sleep 30` 进程能脱离。
3. **启动 vd**：日志应依次出现 `child start` / `VD created` / `moved to display` / `capture ready`，
   10 秒后两端各有一行 `fps=`。
4. **裁切**：改 `x,y,w,h` 后 0.5s 内生效（VdSend 每 15 帧比对一次字符串）。
5. **停止**：`killvds.sh` 打印 `restore:` / `right pane`，窗口回右栏，虚拟屏计数归 0。

## 已知限制

- **仪表端二进制随 APK 分发**：`tools/icrecv2/main.go` 用 `//go:embed assets/*.png` 把三张卡片图
  （`nav.png` 592×104、`speed.png` 310×180、`media.png` 310×180）打进 rodata，缺图直接编译失败。
  这三张图之前不在仓库里，现已补到 `tools/icrecv2/assets/`，用
  `GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0` 编译（`bash tools/icrecv2/build.sh`），
  产物 ELF32/ARM、无 INTERP、无 DYNAMIC（纯静态），可复现（两次构建 md5 相同）。
  APK 内的副本与源码产物 md5 一致；`VdCtl` 先看仪表有没有，再比车机上文件大小，都没有才推。
  注意 `GOARM=7` 依赖 VFP（i.MX6 的 Cortex-A9 有）；若仪表换成更老的核，需要改 `GOARM=5` 重编。
- **私钥在应用私有目录**（`files/adbkey.der`）：能反编译提取，拿到的人只要网络能到 5050 就能 shell 进车机。
  只能在内部使用；撤销方式是在开发者选项里「撤销 USB 调试授权」。
- **adbd 必须常驻 TCP 端口**，这是运行时前提，代码保证不了。
- **`am display move-stack` 依赖 OEM 保留 `am`**，与 PC 脚本风险相同，没有变差。
- 首次启动若仪表上没有接收程序，会先推 2.4MB 到车机、再经 HTTP 中转（约 10–30s），之后不再重复。

---

# v0.5：认证打通 + 三个功能缺口

## 1. AUTH 为什么一直失败（结论，别再重查）

两个独立原因，缺一不可：

1. **包头 `data_check` 必须为 0。** 协议版本 ≥ `A_VERSION_SKIP_CHECKSUM (0x01000001)` 之后，
   这个字段不再用 CRC32，adbd 收到非 0 会**静默丢包**（不报错、不重发、socket 也不关）。
   以前算真 CRC，表现就是「发了公钥/签名，对方毫无反应」。
2. **这台 ROM 的 adbd 不认 AOSP 标准签名。**
   AOSP 是 `RSA_verify(NID_sha1, token, …)`（先 SHA-1 再签），这里被拒；
   把 **token 本身当摘要**塞进 SHA-1 DigestInfo 再签，一次通过。三次实验 100% 复现。

所以 `Adb.SIGN_MODES = {"raw", "SHA-1", "SHA-256"}`，同一个 socket 上收到新的 AUTH TOKEN 就换下一种，
既覆盖这台车机，也保留通用设备要的标准路径。

> 附带纠正：**授权弹窗上的指纹是 app 自己的 key**（AB:DE:CC:86…），不是 PC 的 `~/.android/adbkey`。
> 之前拿 PC 私钥去验签、去比对，方向全错。

## 2. 遥控入口（PC 侧驱动）

> **2026-11-05 UX-11 起，动作表与命令拼装统一由 `tools/vdcast.sh` 负责，
> 语义说明见 [`遥控入口-act-v1.md`](遥控入口-act-v1.md)。** 本节只留结论，不再维护表。

```bash
bash tools/vdcast.sh <动作> [--pkg …] [--mode vd|main] [--crop x,y,w,h] [--crect x,y,w,h]
bash tools/vdcast.sh table      # 看全部动作
```

三条硬规矩（踩过坑，写在脚本里了）：

1. **参数在前、`--es act` 最后** —— `am start` 会把 act 之后多出来的裸参数当 data URI，
   `--es act crop 1,2,3,4` 的坐标就是这样丢的（B0 基线 §3.3）；
2. **参数只属于声明消费它的动作**（`act=status` 不会顺手改 pkg）；
3. **必需参数缺失直接拒绝**，不再静默沿用界面旧值。

旧动作 `vd` / `main` / `cluster` 已删除，分别改成 `start --es mode vd|main` 与 `area`。

用途：调 UI 时不用在车机屏上摸黑点按钮；也方便写自动化。

## 3. 三个功能缺口

| 现象 | 根因 | 修法 |
|---|---|---|
| 投出来是主屏 / 投屏 app 自己的画面 | 目标包装了没运行 → VdSend `findRootTask` 抛异常退出 → 静默退化成主屏采集 | `VdCtl.ensureTargetApp()` monkey 拉起 + 轮询 10s；`startSender()` 传 `--pkg`；VdSend 内再兜一层自拉起 |
| 没有选应用的入口 | 原来 pkg 只能手输 | `candidateApps()` = 运行中任务 ∪ `pm list packages -3`，去重、排除自身；列表显示「应用名 (包名)」，`●` 标运行中 |
| 看不到裁切框 | 只有输入框 | `CropView` + `CropActivity`：`screencap -d <vd>` 当底图（看到的就是投出去的画面，运行中也能调），绿框 + 四角把手 + 三分线 + 尺寸标签，默认锁 1920/568=3.38 |

## 4. 车机 UI 的两个硬约束

- **右下角常驻语音助手悬浮球会盖住并吃掉点击。** 所有可点控件一律靠左，
  裁切底栏右侧留 `dp(170)` 空位，主面板把「框选/应用」挪到行首。
- **density ≈ 0.9**，dp/sp 折算后小到点不准。字号统一下 `COMPLEX_UNIT_PX` 直接写像素，
  `dp()` 统一乘 2.4 补偿。

## 5. 裁切链路的一个真 bug

VdSend 只认 `/data/local/tmp/vdcrop.txt`（热重载也是盯着它改）。
界面上改了输入框但没点「应用」就启动 → 实际用的还是上一次的裁切。
现在 `VdCtl.start()` 开头就把 `cfg.crop` 落盘，输入框在启动时是权威值。

## 6. 安装（每次改代码都要走）

`pm install` 被 `isPlatSigned:false` 拦死，只能走图形安装器 + 键盘导航，已固化进 `tools/installapk.sh`：

1. `am force-stop com.android.packageinstaller` —— 否则残留的 `InstallSuccess` 页会把新 Intent 吞掉；
2. 轮询 `dumpsys activity activities | grep topResumedActivity` 等到 `PackageInstallerActivity`
   （本 ROM **没有** `mCurrentFocus`，`dumpsys window` 查不到）；
3. `keyevent 66`（继续页默认焦点就在「继续」）→ `keyevent 22` + `66`（更新页默认焦点在「取消」）；
4. 用 `lastUpdateTime` 是否变化判定成功，失败自动重试三轮。

## 7. 实测基线（P7，无线连仪表 172.20.1.40:5560）

- 车机采集稳定 30fps，单帧处理 9–10ms，发送 ~7.4MB/s
- 仪表端合成 11–17fps —— 瓶颈在仪表解码（`dec` 48–55ms/帧），不是网络
- 停止后：发送端 0、虚拟屏 0、目标窗口 bounds 复原、仪表 `RESTORE_OK`

---

# v0.7 追加：按矩形出帧 / 实时预览 / 面板重做

## 1. 发送尺寸 = 仪表上那块矩形

以前 `VdSend` 写死 `OUT_W=1920, OUT_H=568`，无论仪表上开多大都先缩到满尺寸，
接收端再缩一次到矩形——两道最近邻、带宽白花 8 倍。

现在 `OUT_W/OUT_H` 从命令行参数取（`VdCtl` 用 crect 的宽高传），`setSize` 直接给目标尺寸，
**缩放交给 SF**（有滤波，质量比 CPU 最近邻好），接收端 1:1 贴。

`--child 550 384 30` 里的数字以前解析了但没用，是个纯粹的漏改；
`/data/local/tmp/vdsend.log` 的 `child start ... out=550x384` 可以直接核对。

## 2. 比例不一致时自动适配源裁切

源裁切（车机画面）和目标矩形（仪表）各自框选，比例很容易不一样，
`setSize` 会把画面直接压扁。`VdSend.cropForOut()` 的处理：以裁切框中心为锚点
**等比扩展**到目标比例（只扩不缩，宁可多看到一点内容），撞到 2400×1200 边界再反向收缩。

日志：`crop fit 960,0 1440x1080 -> 960,38 1440x1005 (out 550x384)`。
框选页的「锁比例」勾着时不会触发。

## 3. 接收端

- 帧尺寸 == 矩形 → `blitExact()` 整行拷贝（copy + 0 像素改 1），不做 xmap；
- 落屏只刷矩形那几行**整宽行**（一次 pwrite），默认满宽仍走原来的整带路径；
- 首帧打一行 `frame WxH -> dst x,y WxH (1:1|scale)`，一眼看出走的哪条路。

实测 qqlive → 750,220 550x384：发送 `out=550x384`，仪表 **30.1fps**、走 1:1；
改之前同样内容 20fps 上下。rect 外原车画面（车模、胎压、里程、状态栏）由
`repaintOutside()` 用 fb0 补，截图见 `shots/dst_1to1.png`。

## 4. 实时预览（PreviewActivity）

发送端本来就采好帧了，预览只是多播一份**不压缩 RGB565**：
车机零编码、APK 零解码（`copyPixelsFromBuffer` 直接进 `Bitmap`），
550×384 一帧 422KB、10fps 才 4MB/s，回环带宽用不完。

- `VdSend --preview <port>`：只绑 `127.0.0.1`，覆盖式单槽队列，**没有客户端时整条路径直接返回**；
- 预览页 `onPause` 就断开，发送端日志 `preview client out n=0`；
- 实测预览 7fps（瓶颈是整窗重绘，不是链路），顶部显示 `分辨率 / fps`。

## 5. 面板重做（v0.7）

暗底 + 卡片 + 蓝/红强调，字号全部按像素给（车机 density≈0.9，sp 折算后小得点不到）：

- 布局：`顶栏(标题+状态chip) → ScrollView(三张卡) → 固定高度日志卡`。
  窗口只有 1440×1080，日志卡固定 `dp(122)`，上半屏才不会被挤出去；
- `ScrollView` 必须 `setFillViewport(true)` + 子视图显式 `MATCH_PARENT`，
  否则按「无宽度约束」测量，行内 `weight` 的输入框会把右边按钮挤出卡片；
- 按钮统一胶囊样式，一律靠左：右下角常驻语音助手悬浮球会吃掉点击；
- 三个页（主面板 / 裁切 / 仪表区域）样式统一，截图 `shots/ui_final.png`、`shots/ui_pick2.png`。

## 6. 又一个「同尺寸 ≠ 同内容」的坑

`VdCtl` 原来按文件大小跳过推送：
- dex 70KB 改几行，大小不变 → 车机跑旧逻辑（日志里还是 1920×568）；
- 仪表端 3.4MB 二进制同理，改完显示逻辑后仪表还在跑旧版。

现在：**dex 每次必推**（70KB 不值得省），**仪表端二进制比对 md5**。
这次实测就抓到一次：车机 md5 已经是最新、仪表上那份还是旧的，大小完全一样。

---

# v0.8 追加：预览 30fps / 工具只核对一次 / 顶部流程条

## 1. 预览页重做（7fps → 30fps）

画面内容始终是**裁切 + 缩放之后**的那一帧 —— 预览走的是发送端已经处理好、
准备发给仪表的那块 `txBuf565`，不是原始整屏，所以「预览看到的 = 仪表上看到的」。

帧率之前上不去的真正原因不是链路，是渲染：

- `ImageView.setImageBitmap` 每帧都要走 measure/layout/invalidate，
  1440×1080 的整窗重绘把上限钉死在 7fps；
- 改成 `SurfaceView` + 独立绘制线程，只 `lockCanvas(dirty)` 那块画面区域，
  空帧直接跳过（帧序号没变就不画）；
- **不要用 `lockHardwareCanvas()`**：这台车机上它撞 `libhwui` 的
  `SkiaOpenGLPipeline::getFrame()` 断言，native 层 SIGABRT，
  `try/catch` 完全拦不住（进程直接死，`Fatal signal 6 SIGABRT in RenderThread`）；
- 软件 canvas 的双线性放大很贵，`Paint` 不设 `FILTER_BITMAP_FLAG` 走最近邻，
  2 倍整数放大下肉眼几乎看不出差别，速度快 5 倍以上。

发送端同时去掉了预览侧的 33ms 节流：主链路本来就是 30fps，再拿
`now - last < 1000/30` 去卡，抖一帧就被砍，实测卡在 25fps。
需要限流时交给 previewLoop 的「最新帧覆盖」自然丢帧即可。

实测：550×384，**30fps** 满帧，回环带宽约 12MB/s。

## 2. 画面边框

预览画面四周画一圈蓝色细线（`drawRect`）+ 四角加粗短角标，贴的是画面的
真实边界（FIT 之后的 dst 矩形），不是屏幕边界 —— 一眼看出裁到哪儿。
顶栏常驻 `车机裁切 x,y,w,h → 仪表 x,y,w,h  分辨率 fps`。

## 3. 工具同步：开一次 App 只核对一次

之前的做法是每次点「启动」都推 dex + 比对仪表二进制 md5。用户说得对：
**发行后这些文件不会变，只有调试期才天天改**。

现在的判定链：

| 场景 | 行为 | 耗时 |
|---|---|---|
| 本次运行第一次启动 | 推 dex（77KB）+ 按 md5 核对仪表二进制 | 秒级 / 首次约 1 分钟 |
| 本次运行再次启动 | 文件存在性探测 | 3 秒 |
| 杀进程重开 App | prefs 里的 `binMd5` 命中 → 只探文件在不在 | 3 秒 |
| 车机/仪表重启清了 /tmp | 探测失败 → 自动重投 | 一次完整投送 |
| 换了 APK 版本 | md5 对不上 → 重新核对 / 重投 | 一次完整投送 |

`prefs.binMd5` 记录「上次成功投送到仪表的那一份」，这是跨进程记忆，
比每次去仪表问 md5 便宜得多。

顺带修掉两个把启动拖慢的坑：

- `Icrpc.run` 是 `(cat frame; sleep N) | nc` 的实现，**hold 秒数就是固定等待成本**，
  不是超时。查 md5 原来给 6 秒、relay 给 90 秒，等于每次白等；
  现在 md5 查询 4 秒、探测 3 秒、relay 55 秒（wget 3.4MB 实测 10–30s）。
- 之前 relay 结束还有一次「车机上已有同一份 → 跳过推送」，但代码仍然往下走
  完整的 HTTP 中继，白等 90 秒。现在命中就直接返回。

## 4. 顶部流程条

`连接 → 选应用 → 裁切画面 → 仪表区域 → 启动投屏 → 预览` 六步常驻在标题下面，
已完成绿底打勾、当前蓝底、未做灰底。以前这套流程只写在日志第一行，
一开始投屏就被刷走了。

状态来源：`stepDone[]`，各按钮/回调处调 `step(i, true)`；
启动失败、停止投屏会把「启动投屏 / 预览」退回未完成。
打开 App 时从 prefs 恢复 `pkg/crop/crect` 并直接打勾，一眼看出只差哪一步。

## 5. 又一个跨线程碰 View 的坑

`startWith()` 跑在工作线程，里面直接调 `setState("启动中…")` 改 TextView
→ `CalledFromWrongThreadException`，异常被 `run()` 的 catch 吞掉，
表现是**日志打完「仪表端程序已内置」就没了，投屏压根没启动**。
`setState` 内部改成 `runOnUiThread`。（`print()` 早就这么做了，`setState` 漏了。）

---

# v0.9 追加：启动时间 100s → 10s

## 1. 真凶：`hold` 不是「超时」，是「固定 sleep」

仪表管理通道是 `(cat 帧; sleep N) | nc 仪表 30510`。这个 `sleep` 是必须的
（toybox nc 在 stdin EOF 后立刻收尾会丢回包），但它同时决定了**每次调用至少花 N 秒**：

| 调用 | 原 hold | 说明 |
|---|---|---|
| 杀旧接收端 | 10s | 只是 kill 一下 |
| 起接收端 | 10s | 只是 setsid 起进程 |
| 查二进制 md5 | 4s | 一条 md5sum |
| 取接收端日志 | 8s | 一条 grep |
| 中继 3.4MB | 55s | 真正需要长 |

启动链路上有 5 次这样的调用，光「等待」就 40 秒。

## 2. 改法：哨兵 + 进程组

命令末尾追加 `echo __ADBPROBE_RPC_END__`，车机侧把它塞进后台、每 100ms
`grep` 一次回包文件，看到哨兵就把**整个进程组**杀掉返回。`hold` 从此只是上限。

```sh
setsid sh -c '(cat $F; sleep HOLD) | nc 172.20.1.40 30510 > $O 2>&1' & P=$!
i=0; while ! grep -aq __ADBPROBE_RPC_END__ $O; do ... ; done
kill -9 -$P; cat $O
```

两个细节是踩出来的：

- **必须 setsid 再杀进程组**。只 `kill` nc 的话，管道里的 `(cat; sleep N)`
  还活着，adb shell 会话要等它 sleep 完才收尾 —— 实测 8 秒一秒不少，
  白折腾（`ticks=1` 说明哨兵是秒到的，但 adb 就是不返回）。
- 哨兵要按**子串**从输出里去掉：命令末尾没换行时，会和最后一行粘在一起
  （`1__ADBPROBE_RPC_END__`），按行删不掉。顺带把 `~qA`（回包头的 7e 71 41）
  这类可打印的二进制残留也过滤了。

一次普通查询：固定 6 秒 → 约 1.5 秒（其中大半是 adb 往返）。

## 3. 少调用、少等待

- **杀旧 + 起新 + 数活口合成一条 RPC**（原来 2 条，光 hold 就 20 秒）。
  起进程写成 `./icrecv_"dbg"` 是有意的：命令文本会出现在执行它的 shell 的
  cmdline 里，直接写 `./icrecv_dbg` 的话，数进程那句 `grep -F "$PAT"`
  会把 shell 自己也数进去，永远报「残留 1 个」。
- **「等旧进程退出」改成轮询**（原来固定 `sleep 2`）：Go 进程 0.2 秒就走了，
  但这一步不能省 —— 端口没释放，新进程 bind 会失败。
- **接收端就绪以 `LISTEN` 日志为准**（原来固定 `sleep 2`）：实测 0.3–0.8 秒。
- **发送端就绪以 `capture ready` 为准**（原来固定 `sleep 10`），
  第一行 `fps=` 是 2 秒时才打的，顺手把它挪到 [7/8] 再取。
- **[7/8] 不再 `sleep 10`**：仪表侧轮询日志里出现 `frames=` 就走（顺带给
  VdSend 时间打出 `fps=`），再取一次车机速率。
- **[8/8]** 异常计数与存活检查合成一次 adb 往返。
  `grep -c` 在 0 匹配时退出码是 1，所以不能写 `|| echo 0`（会多打一行变 `0 0`）。

## 4. 实测

| 场景 | 之前 | 现在 |
|---|---|---|
| 冷启动（App 冷启 + 连接 + 投屏） | ~55s | **10s** |
| 从点「启动 vd」到就绪（已连接） | ~50s | **约 6s** |
| 运行中重启 | ~55s | **11s** |
| 仪表端被清空、需重投 3.4MB | ~90s | **14s**（重投本身 2s） |
| 停止投屏（含仪表还原） | ~40s | **5s** |

`shots/ui_v09.png` 是重投那次的面板截图。

---

# v1.0 追加：帧率 30 → 60（接收端落屏路径重写）

## 1. 先把上限是谁卡住的说清楚

改之前仪表侧打的是 `frames=N 30.1fps`，而发送端的日志是 `fps=30`。
**30.1 不是接收端的天花板，是发送端 `targetFps=30` 节流的产物** —— 接收端
刚好能追平，所以看起来"卡在 30"。只动接收端不可能涨，必须两头一起动。

## 2. 接收端：每帧把数据写了两遍

`flushMid` / `flushFrame` / `flush` 三个函数都是这个结构：

```go
copy(d.fb[off:end], d.back[off:end])   // ① 写进 mmap 的 fb
d.writeBack(off, end)                  // ② pwrite 到 /dev/fb1
```

② 才是真正让画面上屏的路径（`writeBack` 的注释早就写了：mmap 区域与硬件
实际扫描的显存可能不一致）。① 是历史遗留 —— 早期以为 mmap 才是路径，后来
换成 pwrite 赢了，但 copy 没删。**1.47MB/帧的重复写，白烧显存带宽。**

v1.0 默认去掉 ①（`ICFBCOPY=1` 可恢复做对照）。

## 3. 接收端：只写投屏矩形那一块

`flushFrame` 原来按**整宽行**写：1920 宽里只有 550 宽是投屏内容，
每帧搬 1.47MB，其中 1.05MB 是左右两侧的原仪表内容。

v1.0 改成 `writeBackRows`：按 `dstW` 窄写，384 行 × 1100 B = 422 KB。
代价是 384 次 pwrite（每次 3–5 µs，约 1.5 ms 系统调用开销），
换回约 10 ms 的显存带宽。任一行写失败就整块重写一遍兜底，宁可慢不留花屏。

`ICROWS=0` 退回整宽。

## 4. 接收端：分项计时补全

原来 `draw` 一栏混着「blit + 卡片 + 落屏」，看不出落屏占多少。现在拆成：

```
frames=600 59.8fps (recv 550x384) read=... dec=... draw=... flush=... sub=...
```

## 5. 发送端：省掉一次整帧中转 + 21 万次循环

`captureViaSF` 的 565 路径原来是这样：

```
copyPixelsToBuffer → px（422KB 中转缓冲）→ 逐字节循环 21 万次 → frame565
```

`frame565` 本身就能包成 ByteBuffer，`copyPixelsToBuffer` 直接落进去，
只剩下一次原地扫描（色键 0→1）。这一趟在 60fps 下值 3–4ms。

色键修正**必须留在发送端**：接收端走满宽 `direct` 直写时是不查像素的，
漏过来的 0 会直接变成黑洞。

## 6. 发送端：targetFps 30 → 60

车机侧 `captureDisplay` 单帧 11–12ms，`1000/60 = 16.7ms` 吃得下；
矩形投屏后一帧只有 422KB，接收端解码 + 窄行落屏也进了 10ms 内。

跑不满时车机会自动落到实际能到的帧率（`used < gap` 才 sleep，撑不住就
连续采集不再等），不会比 30 更差。

## 7. 实测：30.1 → 58.6fps

车机 `eth0 172.20.1.45`、仪表 `172.20.1.40`，PC 走 `wlan0 192.168.31.210:5050` 连 adb。

```
车机 VDS| fps=62 cap=51682 proc=51682 sent=47554 bytes/s=7325523 procMs=5 lastComp=117536
仪表 frames=2160 58.6fps (recv 550x384) read=1.27ms dec=8.13ms draw=4.57ms flush=3.25ms | total=17.2ms
```

| 项 | v0.9 | v1.0 |
|---|---|---|
| 车机采集 | 30fps（被 targetFps 卡死） | **62fps** |
| 车机发送 | 30fps | **62fps**（cap/sent 同步增长，无积压） |
| 仪表显示 | 30.1fps | **58.6fps** |
| 仪表 flush | ~22ms（整宽 copy + pwrite） | **3.2ms** |
| 仪表整帧 | ~33ms | **14.4–17.2ms** |

`shots/v10_60fps.png`：中间投屏区、左右里程/胎压/车模全部正常，边缘无黑洞无错位。

## 8. 车机侧的真凶：LZ4 的「匹配补登记」

第一次跑 60fps 时车机是 `cap=1989 sent=1692`，差 297 帧且**每 2 秒扩大一次** ——
采集端跑到了 62fps，但 txLoop 只发得出去 47fps，中间全被单槽队列覆盖丢掉。

原因是 `LZ4.compress` 在找到匹配后，把整个匹配区间**逐位置重新 hash 登记**：

```java
for (int p = ip + 1; p < end && p < mfLimit; p++) {
    table[hash(src, p) & (HASH_SIZE - 1)] = p;
}
```

画面里大片渐变/纯色的匹配长度动辄几百字节，这个循环等于**整帧每个字节都 hash 了一遍
再补一遍**。改成只补前 4 个位置（标准 lz4 fast 是完全不补的），压缩率略降
（117KB/帧，原来 120KB），txLoop 直接追平 62fps。

顺带把哈希表从「每帧 new 128KB + fill」改成静态复用。

## 9. 仪表侧：`time.Now()` 差分不可信

改完之后 `sub`（卡在 `d.submit()` 上那一段）打出 36 秒，而同一批的 `total`
只有 16.4ms —— 同一帧里 `t4-t3` 不可能比 `t4-tR` 大。换了几种算法都一样，
最后确认是内核 3.0.35 的时钟源偶发跳变，单次 `time.Now()` 差分不可信。

日志里只保留 `read/dec/draw/flush | total`：`total` 与 `frames/elapsed`
同量纲、互相自洽，判断性能看它就够了。那个残差栏位直接删掉，不留痕迹误导人。

## 10. 装车：这台 ROM 不认 `adb install`

```
adb install -r AdbProbe.apk
Failure [INSTALL_FAILED_INVALID_APK: isByAdb:true
         isPlatSigned:false cannot install: com.probe.adbprobe]
```

只认平台签名，普通签名（含 debug keystore、v2+v3 全开）一律挡。唯一通路是
**图形安装器 + 键盘导航**，已固化在 `tools/installapk.sh`：

```bash
bash tools/installapk.sh adbprobe/AdbProbe.apk com.probe.adbprobe adbprobe.apk
```

脚本里两个关键点（都是踩过的）：

1. 安装器是**异步**起的，`sleep` 固定秒数经常按键时窗口还没到前台，键全打在
   别的应用上 → 轮询 `topResumedActivity` 确认 `PackageInstallerActivity`
   在前台再按。
2. 「要更新此应用吗」页**默认焦点在「取消」** → 必须先 `KEYCODE_RIGHT`
   再 `KEYCODE_ENTER`。第一屏「继续」才是默认焦点，直接 ENTER。

每轮结束用 `dumpsys package | grep lastUpdateTime` 判断是否真装上，没变就重试
（最多 3 轮），不靠猜。

## 11. 版本号：manifest 和面板一直是脱节的

面板日志一直印 `车机投屏控制台 v1.0`，但 `AndroidManifest.xml` 里还是
`versionCode="9"` / `versionName="0.9"`。后果是：

```bash
adb shell dumpsys package com.probe.adbprobe | grep versionName
#   versionName=0.9        ← 装了新版还是这个值
```

从这个字段判断装没装上新版会**直接误判**。已改为 `versionCode="10"` /
`versionName="1.0"`，跟面板同步。以后升版本两处一起改。

装完的校验方法（不依赖版本号，直接比内容）：

```bash
P=$(adb shell pm path com.probe.adbprobe | sed 's/package://' | tr -d '\r')
adb pull "$P" ./car_check.apk
md5sum ./car_check.apk adbprobe/AdbProbe.apk                    # 整包一致
unzip -p ./car_check.apk classes.dex | md5sum                    # dex 一致
```

本次两份 md5 都是 `f9dbc84754e0c5d4d7058c2a6c1a1e8f`，dex 都是
`eb86e5bcaafa3cc3f037ff3e4dde8fd2`。

装完先 `tools/stopcast.sh` 把上一轮残留收干净（`RESTORE_OK` + 车机 0 进程），
再 `am start` 让用户从零走一遍流程，避免面板一进来就看到「已连接」的中间态。

# 改造记录（按批）

> 与 `docs/审计-四类改造-答复与需求确认-v1.md` 的批次一一对应。
> 每条记录「改了什么 / 怎么验的 / 还有什么没做」，便于回滚与交接。

## B5 第四批：换应用收尾用错包名（修）+ 预览首帧埋点（2026-11-05 19:05）

### 改了什么

| 文件 | 变更 |
|---|---|
| `VdCtl.java` | **PERF-05 修复**：`start()` 开头先存 `prevPkg = activePkg`，`[2/8]` 停旧发送端改用 `prevPkg`（原来用 `cfg.pkg`，也就是**新**包名），换应用时打一行提示 |
| `PreviewActivity.java` | **PERF-08 埋点**：从 `onCreate` 计时，第一帧真正上屏时打 `[preview] 首帧 Nms (static|live)` |

### 怎么验的

| 项 | 结果 |
|---|---|
| 旧行为复现（设备侧，不重建 APK） | 投着 qqmusic 时跑 `killvds stop com.bilibili.bilithings`（旧代码就是这样）→ `restore: nothing to do` 恢复的是 bilibili，**qqmusic 落到 `bounds=[0,0][2400,1200]`（全屏）** |
| 修复后 A→B 切换 | 日志 `换应用：qqmusic → bilibili，先按上一次的应用收干净`；A 回右栏 `[960,0][2400,1080] visible=false`；B 在虚拟屏；虚拟屏数 1 |
| 切换后停止 | `收尾完成 1685 ms`、`RESTORE_OK`、残留 0 |
| 切换耗时 | 遥控 → 就绪 **4.27 s**（= 3.04 s 普通启动 + ~1.2 s 旧应用收尾） |
| 预览首帧 | `[preview] 首帧 114ms (static)` / `81ms (live)` |

### 没做的

- 切换耗时没有单独优化：那 ~1.2s 是「把旧应用从虚拟屏搬回右栏」的必要成本，硬砍会回到 PERF-05 的残窗问题。
- 预览首帧只加了埋点，没有为它做优化 —— 100ms 量级不是瓶颈。

## B5 第三批：停投屏 4.95s → 1.69s（2026-11-05 18:50）

> 停止链路原来由几段固定等待拼成（`sleep 2` + `sleep 1500` + `sleep 2000`），加起来就是 4.3s。
> 这批不碰协议，只把「等固定时间」换成「等到状态对为止」。

### 改了什么

| 文件 | 变更 |
|---|---|
| `tools/remote/killvds.sh` | TERM 后 `sleep 2` → 轮询到「真的没有 VdSend」；新增 `count_procs()`（只数 `/proc/<pid>/cmdline` 以 `app_process` 开头的，避免把 grep 自己数进去） |
| `asx/.../VdSend.java` | ① `restoreRightPane()`：`am start` 后不再 `sleep 2000`，直接 `am task resize`（幂等）再核实，失败补一次；② 搬回主屏后 `sleep 1500/1200` → 核实 `displayId`，没搬走才复查；③ `am stack list` 三次 → 800ms 短缓存（搬移/resize 后失效）；④ restore 路径删掉 `systemMain()`（只用 `am` 命令行，不发 binder） |

### 怎么验的

| 项 | 结果 |
|---|---|
| 应用侧「收尾完成」 | **4955 ms → 1629 / 1693 ms** |
| 其中 `killvds stop` | 4.35 s → **~0.42 s**（TERM 68ms + restore 211–216ms + 轮询 ~0.1s） |
| `RESTORE_OK` / 残留 / 窗口 | 有 / 0 / `[960,0][2400,1080]`（直测 1 轮 + 压测 3 轮一致） |
| 开始投屏 | 仍 3.0s（本批不涉及） |

### 踩到的坑（自己挖的，已写进注释）

第一版把 `sleep 2` 换成 `list | wc -l` 轮询，**永远看到 1 个进程**、20 轮跑满 2 秒 ——
那条 `grep -F com.probe.asx.VdSend` 自己的命令行里就写着模式串，把自己数进去了。
改成只数 `app_process` 实例后，轮询降到 ~0.1s。

### 没做的

- `killvds.sh` 里仍保留「上限 2s」的兜底：真遇到杀不掉的进程，行为等价于原来的 sleep 2（不会更慢）。
- restore 里 `am start` 仍然是新起一个 `am` 进程（约 0.4s），没有换成 binder —— 换的话要重新引入 `systemMain()`，得不偿失。

## B5 根因修复：Adb 单连接并发会吞掉对方的 CLSE（2026-11-05 18:36）

> 「stop 偶发卡 45s」查到底了：**不是 `killvds.sh stop`、也不是 `--restore` 慢**，
> 是 APK 的 Adb 客户端在一条连接上并发跑两条命令时，把对方的收尾包当「别的流」丢掉。

### 改了什么

| 文件 | 变更 |
|---|---|
| `Adb.java` | `exec / execRc / push / pull / connect / close` 全部 `synchronized` —— 就算以后又有人在别的线程直接调，也只会排队，不会互相吞包 |
| `MainActivity.java` | 3 秒补测从「裸线程直接 `a.exec`」改成走 `worker`（与 stop 串行），从源头去掉并发 |

### 证据（同一台车、同一条 stop 命令）

| # | 条件 | 结果 |
|---|---|---|
| ① | PC 直连 `adb shell killvds.sh stop`，8 轮 | 8/8 正常（4.6–4.9s）→ 命令本身不慢 |
| ② | 走 APK `act=stop`，每秒采样，10 轮 | 0/10（采样拖慢 restore，没撞上窗口） |
| ③ | 走 APK `act=stop`，加压 30 轮 | 0/30（新补测已走 worker，不并发） |
| ④ | **故障注入**：补测改回裸线程 `a.exec` | **第 1 轮复现**：`停发送端超过 15000ms`，收尾 15976ms |
| ⑤ | ④ + 给 Adb 加 `synchronized` | **8/8 正常**（5.9–6.5s） |
| ⑥ | 最终包：锁 + 补测走 worker | 4/4 正常；启动 3.0s、小灯 3.3s 内补齐 |

### 机制

`Adb.readAll()` 只处理 `arg1 == 自己流` 的包，其余 `continue` 丢掉 —— 包括对方的 `CLSE`。
两条命令并发时，先读完的那条把另一条的收尾包吃掉，后者永远等不到结束，直到 `SocketTimeoutException`；
`execRc` 超时又会 `close()` 整条连接，后续 kill -9 / 二次 stop / 残留检查全报「未连接」，
于是整条收尾中断（17:53 那两次把接收端留在了仪表上）。

时间线也对得上：两次卡死都是「就绪后 ~2.5s 发 stop」，补测在就绪后 3s 触发，正好落进 `[1/4]` 的 4.3s 窗口。

### 没做的 / 更正

- **口径更正**：此前记成「`killvds stop` 里 `app_process --restore` 偶发卡死」是错的；④⑤ 两次里 restore 都在 0.3s 内打出 `nothing to do`。
- 复现脚本留在 `docs/materials/b5/repro/`（不入库），后续再出现类似「一条命令莫名超时」可以直接套用这个四步对照法。

## B5 第一刀：首帧即报 + 停止兜底 + 并行起两端（2026-11-05 18:16）

> 目标：启动快一点。**先测后改** —— 第一步的重测（`docs/B5-启动链分解-v1.md` §1–§5）推翻了「2 次 RPC ≈3.5s」的旧结论，
> 真正的成本是「VdSend 起屏 1.9 s」+「等接收端第一行 frames= 2.0 s」。这一批只做这三处，其余 PERF 项没动。

### 改了什么

| 文件 | 变更 |
|---|---|
| `tools/icrecv2/main.go` | **A**：接收端第 1 帧就写 `frames=1 first frame (recv WxH)`（两条帧路径各一处），之后仍每 60 帧打聚合行 |
| `VdCtl.java` | **C**：`startSender()` 拆成 `launchSender()`（只拉起，不等）/ `awaitSenderReady()`（等 capture ready）；`start()` 改成「先拉起发送端 → 起接收端 → 等 capture ready」 |
| `VdCtl.java` | **B**：`[1/4]` 停发送端 45 s → 15 s；新增 `killSender()`（超时 → 重连 → kill -9 → 再补一次 8 s）与 `reconnectQuietly()`（用 `adb.endpoint` 重连） |
| `MainActivity.java` | 3 秒后的补测从「只 grep 车机 fps」改成跑一次完整 `refreshHealth()`：A 之后启动不再等那 2 秒，两侧 fps 当场还没打出来 |

### 怎么验的

| 项 | 结果 |
|---|---|
| 启动（5 轮，驻车，默认安全区） | **3.01 / 3.03 / 3.05 / 3.04 / 3.05 s（中位 3.04）**，改前 5.15 s，**−2.1 s（−41%）** |
| 分段变化 | `[7/8]` 2.02 → **0.49 s**（A）；`[5][6]` 2.36 → **1.84 s**（C） |
| 判据 | `gotFrames` 仍要求日志真出现 `frames=`；`DST 352,180 1216x360`、`RESTORE_OK` 照旧 |
| fps | 34.9 → 36.8（3 s 后补测），无回退；三层小灯 3 s 内补齐（`车机 60 / 仪表 36.8`） |
| 停止正常路径 | `收尾完成 4955 / 5010 ms`、`RESTORE_OK` |
| 停止异常路径（故障注入） | 预算临时改 300 ms → `停发送端超过 300ms → 重连成功 → kill -9 → 再补一次 → RESTORE_OK，3350 ms`；注入后已改回 15000 并重装 |
| 装机 | `versionCode 3 / versionName 2.2`（不变），18:16 覆盖安装 |

### 两个坑（都写进了代码注释）

1. **并行起法第一版把 VdSend 弄丢**：`setsid … & echo LAUNCHED` 立刻返回，Adb 客户端读到哨兵就关 socket，
   adbd 收掉会话进程组，而 setsid 还没完成 detach → `vdsend.log` 为空、`[6/8]` 空等 15 s。修法：launch 末尾 `sleep 0.5`。
2. **超时后连接已死**：读超时会让 Adb 客户端进入断开态，后续 kill -9 / 二次 stop / 残留检查全报「未连接」，
   整条收尾直接中断（18:13 那次把接收端留在了仪表上）。修法：超时后先 `reconnectQuietly()` 再兜底。

### 没做的

1. `PERF-01…11` 其余项没动（本轮只做启动链收益最大的三处）。
2. `killvds stop` 里 `app_process --restore` 偶发卡死的**根因**没查 —— 只保证「卡住了也能在 ~24 s 内给结论」，没让它不卡。
3. 帧率：车主 18:01 明确不设门槛，本轮不追 60fps；默认安全区四周留白，满足「不覆盖整个仪表」。

## 面板微调：预览入口上移到「启动投屏」上面（2026-11-05 17:50）

> 车主指出：预演已经排在启动之前，面板上的预览按钮却还在主 CTA 下面 —— 按钮顺序和流程条正好相反。

### 改了什么

| 文件 | 变更 |
|---|---|
| `MainActivity.java` | 卡片 3 顺序改成 `预览画面（预演：先看效果，没投屏也能看）` 全宽按钮 → `启动投屏 / 停止` → 面积提示；只动位置与文案，`start()` / `openPreview()` 逻辑未改 |

### 怎么验的

| 场景 | 结果 |
|---|---|
| 打开面板 | 预览按钮在主 CTA 上方，文案完整不截断（`materials/b4/UI09_preview_above_cta.png`） |
| 点预览按钮 | 仍走静态预演：`静态预演：底图 主屏 · 仪表 352,180,1216,360（没有起投屏，零风险）`，`PreviewActivity` 起页，返回键回面板 |
| 装机 | 17:49:41 覆盖安装（版本号不变，`versionCode 3 / versionName 2.2`） |

## B2 收尾：UI-09 流程条「预演 → 启动投屏」（2026-11-05 17:45）

> B2 的最后一条。B4 把静态预演做出来之后，「预演在启动之前」才真正成立 —— 这一批只是把流程条兑现。

### 改了什么

| 文件 | 变更 |
|---|---|
| `MainActivity.java` | ① `STEP_NAMES` 改成 `连接 → 选应用 → 仪表区域 → 裁切画面 → 预演 → 启动投屏`；② `stepDone[4] = rehearsed \|\| previewOpened`、`stepDone[5] = fresh`；③ **新增 `rehearsed` 标记**（与 `previewOpened` 分开）：静态预演/实时预览看过即置位，**启动后不清**，配置一改由 `cfgWatcher` 置回 false；④ `[0/8]` 前打印的流程行同步 |

`rehearsed` 必须独立的原因：`previewOpened` 在 `startWith()` 里被清掉（实时预览要重开），
直接拿它给「预演」打勾，会出现「启动完成反而把预演打回未完成」。

### 怎么验的

| 场景 | 结果 |
|---|---|
| 未投屏 → 静态预演 → 返回面板 | 流程条 `✓连接 ✓选应用 ✓仪表区域 ✓裁切画面 ✓预演 ｜ 6 启动投屏`（`materials/b4/UI09_flow_after_rehearsal.png`） |
| 启动投屏后 | `✓预演 ✓启动投屏` 同时成立（`UI09_flow_after_start.png`）；`DST 352,180 1216x360`、`RESTORE_OK`、`收尾完成 4975 ms` |
| 配置改动 | `cfgWatcher` 立即把「预演」打回未完成（代码级一致，未单独截图） |

### 没做的 / 观察到的问题

1. **启动成功后面板可能被别的应用盖住**：这次虚拟屏把 bilibili 移走时，下层的音乐 App 顶了上来，要手动点一下面板才回前台。B3 只在「停止后」拉回前台；要不要在 `start()` 成功后也拉回一次，归下一轮/B5 定（不影响投屏本身）。
2. 实时预演（`asx --no-send`）仍按答复件 §2.2 留 B5 评估。

## B4 选应用 / 裁切 / 预览（2026-11-05 17:35）

> 六条一起交：选应用页、底图来源、合成预览、crect 锁比例、静态预演、不代关目标应用。
> 全部在驻车条件下上车验过，证据在 `docs/materials/b4/`。

### 改了什么

| 文件 | 变更 |
|---|---|
| `AppGridActivity.java` | **新增**。5 列图标网格 + 应用名 + 运行中角标 + 搜索。数据源 `queryIntentActivities(MAIN/LAUNCHER)`；图标 **3 线程后台解码 + 8MB `LruCache`**（R-05：主线程不取图）；运行中角标由主面板预算后通过 Intent 传进来；搜索同时匹配名称与包名；`label` 等于包名或包名样式（`com.xiaopeng.xxx.App`）的系统内部 activity **过滤掉** |
| `MainActivity.java` | ① `pickApp()` 从 `AlertDialog` 改成起 `AppGridActivity`，`onActivityResult(REQ_PICK)` 回填 pkg（不再直接改 `ctl.cfg().pkg`，维持 UX-17 的快照语义）；② `frameCrop()` 把 `crect` 和由它算出的 `out_w/out_h` 一起传给裁切页（UI-13），并把底图来源 `src` 带过去（UI-02）；③ `openPreview()` 改成两段式：投屏中走实时流，没投屏走静态预演（截一张 → 缓存 → `mode=static`），失败给「先启动投屏再预览」的出口 |
| `PreviewActivity.java` | ① 底图：读 `assets/fb0_pre.png`（1920×720），按窗口等比缩放后作为仪表原版面；② 流画进 `crect` 映射出的矩形并加蓝框；③ 新增 `mode=static`：从 `png` 读一张、按 `crop` 裁好当唯一一帧，不启网络线程、不连 5570；④ 顶栏静态模式写「静态预演（还没投屏）· 底图 <来源>」，不收帧率；⑤ 静态模式隐藏「重连」 |
| `CropActivity.java` | ① `ratio` 改成用 `crect` 直接算（UI-13），锁比例框显示「锁比例 1.00 · 仪表 540×540」；② 左上角新增底图来源角标（UI-02），主屏底图用琥珀色并提示「先启动投屏/预演再框选更准」 |
| `VdCtl.java` | ① `snapshot()` 按「虚拟屏 → 目标应用副屏 → 主屏」选 display，并记 `shotSource` / `shotDisplay`（UI-02）；② `ensureTargetApp()` 记 `launchedByUs`（UX-13）；③ `stop()` 增加「不代关目标应用：…（本次由我们拉起，仍留着 / 本来就在运行，没动过）」一行 |
| `AndroidManifest.xml` | 注册 `.AppGridActivity`；`versionCode 2 → 3`、`versionName 2.1 → 2.2`（R-06：新增 Activity 必须 +1） |

### 怎么验的

| 项 | 手段 | 结果 |
|---|---|---|
| UI-01 网格 | `act=pick` | 5 列图标网格 + 名称 + 运行中角标 + 搜索框（`materials/b4/UI01_app_grid.png`） |
| UI-01 搜索 | `input tap` 聚焦 → `input text bili` | 只剩「哔哩哔哩」，点选后回面板 `目标应用 -> com.bilibili.bilithings`（`UI01_search.png`、`UI01_selected.png`） |
| UI-02 来源 | 投屏中 `act=snap` | 角标「底图来源：虚拟屏 display 100」，底图就是真正投出去的那块（`UI02_source_vd.png`） |
| UI-02 兜底 | 未投屏 `act=snap` | 角标「底图来源：主屏（目标应用不在副屏；先启动投屏/预演再框选更准）」（`UI13_lock_ratio.png`） |
| UI-13 锁比例 | 先 `crect 540,100,540,540`，**未启动**就 `act=snap` | 锁比例框显示「锁比例 1.00 · 仪表 540×540」，框就是正方形（旧版会锁 3.38） |
| UI-03 合成预览 | 投屏中点 `act=preview` | 仪表原版面（READY/PRND/2.5 bar/98947 km/24°C）+ 投屏区里是 QQ 音乐画面，蓝框标边界（`UI03_live_composite.png`） |
| UX-02 静态预演 | 未投屏点 `act=preview` | 顶栏「静态预演（还没投屏）· 底图 主屏 → 仪表 352,180,1216,360」「1440×426」，合成图同屏可见（`UX02_static_rehearsal.png`） |
| UX-13 不代关 | 目标应用先 `pidof` 为空 → `start` → `stop` | 日志「拉起成功」→「不代关目标应用：com.bilibili.bilithings（本次由我们拉起，仍留着）」，stop 后 `pidof` = 31882，两端进程 0 |
| 装机 | `tools/installapk.sh`（图形安装器） | 17:33:51 覆盖安装；`versionCode=3 / versionName=2.2`；`base.apk` md5 `c4904acc6c9c195be8ea661139187bc6` 与本地一致 |
| 回归 | `start` / `stop` 各一轮 | `DST 352,180 1216x360`、`RESTORE_OK`、`收尾完成 5029 ms`、自检干净 |

### 没做的 / 已知残点

1. **UI-09「预演前置」没挪**：静态预演已经可用，但把「预览」挪到「启动投屏」之前要动 8 步流程的编号与流程条，留给下一轮（B4 六条里不含它）。
2. **实时预演没做**：`asx --no-send` 的准入条件（预演时帧率不回退）按答复件 §2.2 留 B5 评估；本轮的静态预演是「必交付」的那一半。
3. **第一版网格返工过一次**：最初没滤掉「label 就是包名」的系统内部 activity，列表里全是 `com.xiaopeng.*`，现场加了过滤（label == 包名，或包名样式：只有字母数字下划线点且带点）。
4. `act=clean` 无残留时仍打「残留已清理」——UX-11 记下的文案问题，本轮仍未改。

### 回滚

```bash
git revert <本批 commit>
```

只动 APK 侧 Java、manifest 与文档，不涉及 `asx/`、`tools/icrecv2/`、投屏协议与仪表端命令行。
想只回「选应用页」：把 `MainActivity.pickApp()` 换回旧的 `AlertDialog` 版本即可，`AppGridActivity` 留着不用不影响。

## B2 收口：UX-11 遥控入口语义统一 + PC 脚本同源（2026-11-05 17:25）

> B2 的最后一条。审计答复 §2.3 的要求：**同一套动作在 APK 与 PC 脚本上都存在且命名一致**，
> 不允许再出现「脚本 7 步 / APK 8 步」的漂移。R3-2 明确「不要求向后兼容」，所以这一批是**改语义**，不是加兼容层。

### 改了什么

| 文件 | 变更 |
|---|---|
| `adbprobe/src/com/xiaopeng/vdcast/Acts.java` | **新增**。22 条动作表（动作 / 消费的参数 / 说明 / 等价界面），纯 `java.*`，离线测试直接编译；含 `ORDER_TIP`（参数在前、act 最后的唯一文案来源）与 `deprecated()` 迁移提示 |
| `MainActivity.java` | 重写 `handleIntent`：动作名统一到 `Acts`；参数只属于声明消费它的动作；必需参数缺失直接拒绝；识别被 am 吃掉的多余参数（data URI / 包名）并点名；旧名给迁移提示；未知 act 打全表；新增 `act=help`。`act=status` 改走 `showStatus()`（与按钮同一条路径）。版本号打印改 2.1 |
| `PreviewActivity.java` | 预览页「启动投屏」下发的遥控从 `act=vd` 改为 `act=start` + `mode=vd`（旧名已删） |
| `tools/vdcast.sh` | **新增**。PC 侧唯一驱动：`build_cmd()` 固定「参数在前、`--es act` 最后」；`table` / `log` / `selftest` / `--dry-run` / `--wait`（前后数 logcat 次数）；只消费声明参数，带错参数直接退出码 2 |
| `tools/startcast.sh` / `stopcast.sh` | 仅加头注：**不经 APK 的裸机直驱（7 步）**，正常流程走 `vdcast.sh`（8 步），两条路径不要混着数 |
| `AndroidManifest.xml` | `versionCode 1 → 2`、`versionName 2.0 → 2.1`（改 APK 必带，便于确认装的是新版） |
| `test/ActsTest.java` | **新增** 44 条断言：表本身、参数契约、迁移、help 覆盖，以及 **三处对账**——`Acts.TABLE` ↔ `MainActivity` 的 `case` ↔ `vdcast.sh` 的 `ACTS` |
| `test/run.sh` | 编进 `Acts.java` / `ActsTest.java` 并执行 |
| `docs/遥控入口-act-v1.md` | **新增**：五条规则、22 条动作表、PC 用法、迁移表、三层验证口径 |
| `docs/APK控制器-v0.2.md` §2 | 旧表换成指针（避免第二份会漂的动作表） |
| `docs/B0-基线数据-v1.md` §3.3 / §8 | 把「data URI」的说法修正为实测结论（纯坐标落成**包名**），复现命令改成脚本写法 |

### 删除的 `act`（按 §2.3 要求在此写明）

| 旧 | 新 |
|---|---|
| `vd` | `start --es mode vd` |
| `main` | `start --es mode main` |
| `cluster` | `area` |

其余动作名保留；新增 `help` 与 `crect`（后者以前只能在 `start` 里顺带生效）。

### 语义变化（会改变既有脚本行为的）

1. **参数不再无条件写进界面**：`--es pkg` 只在 `start` 生效；`act=status` 带 `--es pkg` 会打 `[忽略]`。
2. **必需参数缺失就拒绝**：`act=crop` 不带 `--es crop` → 报错停止，**不再沿用输入框里的旧值**。
3. **只带参数不带 `act` 什么也不做**（以前会静默改界面字段）。
4. 坐标被 am 吃掉时，日志会直接指出「写在了 `--es act` 之后」并给正确写法。

### 怎么验的

| 层 | 手段 | 结果 |
|---|---|---|
| 离线 | `bash adbprobe/test/run.sh` | MockAdbdTest OK；CfgTest `pass=27`；**ActsTest `pass=44 fail=0`** |
| 脚本自检 | `bash tools/vdcast.sh selftest` | `pass=71 fail=0`（每条动作以 `--es act` 结尾、act 之后无裸 token） |
| 构建 | `SKIP_ICRECV=1 bash adbprobe/build.sh` | `BUILD OK`，`AdbProbe.apk` 1438754 B |
| 装机 | `tools/installapk.sh`（图形安装器） | 17:17:45 覆盖安装成功；`versionCode=2 / versionName=2.1`；`base.apk` md5 `c12d50d53f1b037bb75a1c95981bbe6e` 与本地一致 |
| 车机·22 条动作 | `vdcast.sh` + `am start`（驻车，17:15–17:23） | 全部跑过；`help` 打出 22 条表；`crop/crect` 走脚本；`exit` 弹确认框、取消后进程仍在；`exitnow` 进程归零、重开正常 |
| 车机·负例 | `--es act crop 1,2,3,4` | `!! 参数写在了 --es act 之后，被 am 当成 包名：1,2,3,4` + `!! act=crop 缺 --es crop` |
| 车机·启动 | `start --pkg com.tencent.qqmusicpad --mode vd` | `[0/8]` 1216×360(安全区) → `DST 352,180 1216x360`、`frames=60 35.1fps`；`就绪` 用时 4–6s |
| 车机·收尾 | `stop` | 两次 `RESTORE_OK`，`收尾完成 4931 / 4974 ms`，残留进程 0 / 虚拟屏 0，自检「干净」 |

材料：`docs/materials/ux11/`（5 张截图 + 2 份 logcat）。

### 没做的 / 已知残点

1. **`pick` / `area` / `snap` 只验到「能打开、能取消」**，没为旧页面留截图 —— B4 会把它们换成网格页 / 来源角标 / 合成预览，旧页面不值得再取证。
2. **`act=clean` 在没有残留时也会打印「残留已清理」**（实际啥也没清）—— 文案问题，归 B4 顺手改。
3. **B4 加新动作时的同步点**：`Acts.TABLE` + `MainActivity` switch + `vdcast.sh` 的 `ACTS` 三处；漏一处 `ActsTest` 会失败。

### 回滚

```bash
git revert <本批 commit>
```

不涉及 `asx/`、`tools/icrecv2/`、投屏协议与仪表端命令行，回滚只影响遥控入口与面板文案。
若只想恢复旧动作名：把 `MainActivity` 的 `case "start"` 拆回 `vd`/`main` 即可，但 `ActsTest` 会报「表与 switch 不一致」——这是有意的。

## B2 遗留：启动自检 / 一键启动 / 进度条与中断（2026-11-05 16:55）

B2 收口时留下四条，本批做掉三条：**UX-04 启动自检 + 一键清理残留**、**UX-06 一键启动**、**UI-12 进度条 + 可中断**。UX-11（遥控语义统一 + PC 脚本同步）仍留后批。

### 改了什么

**UX-04 启动自检（`VdCtl.scanResidue()` / `Residue` 内部类）**

一次 `exec` 查齐四项，冷启时只跑一次（`residueChecked` 记忆位）：

| 判据 | 来源 |
|---|---|
| 车机发送端残留 | `killvds.sh check` 进程数 |
| 虚拟屏残留 | `dumpsys` 里 `asxvd` 计数 |
| 仪表接收端残留 | `icrpc` 查 `ps icrecv_dbg` 进程数 |
| 仪表未恢复 | `RESTORE` 状态 |

面板顶栏下方新增一条残留横幅：琥珀底色「发现上次残留：发送端 1 · 虚拟屏 1 · 接收端 0 · 仪表未恢复」+ 右侧「清理」按钮。干净态不显示。`act=clean` 一键清理，走 `cleanupResidue()`（停车机 → 停仪表 → 确认恢复 → 清临时文件）。

**UX-06 一键启动（失败统一出口）**

`start()` 各步失败原来只打一行 `!!`，现在统一「停在第 N 步 + 下一步该做什么」。启动失败时 [3/8] 可能已把目标应用拉到前台盖住面板 → 失败后自动 `am start` 把自身拉回前台，错误信息看得见。

**UI-12 进度条 + 可中断**

- `Log` 接口加 `progress(n, total, what)` 通道（默认空实现，老调用方不受影响）。
- 面板流程条下方加进度条 + 当前步骤文案，实时显示「到第几步、在干什么」。
- 中断粒度是「步」：正在跑的那条 adb 命令打断不了（`killvds stop` 最长 4.4s），面板如实写「等当前这一步结束」。`act=cancel` 触发。
- 被丢弃的请求用 `!!` 让日志摘要变红 + 自动展开，不再是静默丢。
- `btnConnect` 纳入 `setBusy`。

### 怎么验的（驻车实车）

| 场景 | 结果 |
|---|---|
| 干净态自检 | 「启动自检：干净（无残留进程 / 无残留虚拟屏 / 仪表已恢复）」 |
| 制造残留（投屏中 `kill -9` 仪表接收端）后重进 | 四项全命中，横幅琥珀色 + 清理按钮 |
| `act=clean` 一键清理 | 3 步走完，横幅消失 |
| 启动失败路径（`com.nonexistent.demo`） | 「停在第 3 步」+「下一步：从运行中任务挑一个」 |
| 正常启动 | `DST 352,180 1216x360` + `就绪`，未被中断逻辑误伤 |
| 中段发 `act=cancel` | `已中断（停在第 7 步，后续步骤跳过）` + 半启动引导 |
| 半启动状态 → 停止 | **RESTORE_OK，4973ms**，两端进程收干净 |

### 回滚方式

- 只回 UX-04/UI-12：`git revert <本批 commit>` 后单独保留 `VdCtl` 的 `stop()`/`cleanupResidue()`（安全相关，不应回退）。
- 残留横幅/进度条都是纯增量 UI，不影响投屏协议与仪表端 compose 命令行。

### 已知残点

- **帧率 31.0fps 偏低**：安全区 1216×360 实投只有 31.0（`dec=15.2ms`，43.8 万像素约 74ns/px），B0 记录的满宽横带 30–39fps 数据不可复现。归 B5 帧率优化处理。
- **UX-11 未做**：遥控语义统一 + PC 脚本同步。

## 口径修订：60 帧从 A 改回 B（2026-11-05 16:20）

> 不是新功能，是**默认值 + 文案**的修订。上一批（B3）已经把面板收口，这批只动「仪表投屏区域」这一件事。

### 为什么改

14:33 定的 A 口径是「承认物理上限 + 按面积分档 + 面板标注实际 fps」，即把选择权交给用户、把话说清楚。
16:00 车主改判为 B：**仪表是安全件，不该默认被投屏占满。** A 虽然诚实，但它把「占满仪表」留成了出厂状态 —— 对安全件来说，默认值比提示更重要。

16:12 再加一条约束：**「可跑满 60 帧」这种话不许出现**。因为帧率还受画面复杂度影响，静态画面和高动态画面不是一个成本，写死档位等于给自己挖坑。

### 改了什么

| 文件 | 变更 |
|---|---|
| `VdCtl.java` | ① 新增常量 `CL_W/CL_H`（1920×720）、`AREA_SAFE`（50 万，仅用于定默认方案）、`DEF_CX/CY/CW/CH`（352/180/1216/360）；② `Cfg` 默认从 `cCustom=false`（满宽横带 1920×568）改成 `cCustom=true` + 安全区矩形；③ 新增 `isFullBandRect()` / `inSafeArea()` / `useSafeDefault()`；④ **删掉 `fpsHint()` 与 `fpsBand()` 的四档预测**，改为 `areaText()`（只说面积）+ `areaNote()`（说方案），`[0/8]` 日志改报「投屏区 1216×360 · 43.8 万像素 (安全区)」 |
| `MainActivity.java` | ① `CRECT_DEFAULT` 改指 `VdCtl.DEF_RECT`；② 卡片标题改「默认安全区 1216×360」；③ 新增「复位」按钮 + 遥控 `act=reset`（`btnReset` 也进 `coords` 打印，便于自动化）；④ `areaHint()` 改为直接产出 `areaNote()`；⑤ `applyClusterCfg()` 解析失败的兜底从「满宽横带」改成「安全区」，并在超区时留一条日志；⑥ 之前存过的旧默认值 `0,72,1920,568` 做一次性迁移 |
| `ClusterPickActivity.java` | ① 底部按钮「整条」改成「安全区」（一键回到 352,180,1216,360）；② `hint` 超区时改说「仪表为安全件，不建议铺满：当前 1216×360 · 43.8 万像素」，不再报帧率；③ `custom` 标志改用 `VdCtl.isFullBandRect()` |
| `test/CfgTest.java` | **新增**，27 条离线断言（`test/run.sh` 自动跑） |

### 默认值为什么是 352,180,1216,360

| 约束 | 取值 | 说明 |
|---|---|---|
| 面积 | 437,760（43.8 万） | 按解码预算推算的上限约 45~55 万，取在 45 万一线之下 |
| 比例 | 1216/360 = 3.3802 | 默认 `crop` 1440×426 是 3.3803，差 0.07%，不会变形 |
| 位置 | x=352, y=180 | 352×2+1216=1920、180×2+360=720，严格居中；不压 72/80 告警带 |
| 缩放 | 0.845× | 原满宽横带是 1.33× **放大**，所以画质反而更锐 |

中途先取过 `1298×384`（49.8 万），太贴上限、几乎没余量，又降了一档。

### 文案怎么变的

| 位置 | 原来 | 现在 |
|---|---|---|
| 面板（安全区内） | `投屏区 550×384（自定义） · 21 万像素，可跑满 60 帧` | `仪表为安全件，建议只用安全区（默认 352,180,1216,360）：当前 1216×360 · 43.8 万像素` |
| 面板（超区） | 同上，只换档位文字 | `⚠ 仪表为安全件，不建议全屏投放：当前 1920×568 · 109.1 万像素`<br>`面积越大帧率越容易掉，建议改回安全区 352,180,1216,360` |
| 框选页 | `投屏区 x,y  w×h` | `投屏区 x,y  1216×360 · 43.8 万像素`；超区加「⚠ 仪表为安全件，不建议铺满」 |
| `[0/8]` 日志 | `投屏区 1920x568，109 万像素，约 30~40 帧（接近满宽，物理上限）` | `投屏区 1216×360 · 43.8 万像素  (安全区)` |

超区那条显式加了 `\n`：整句太长会顶到卡片右边，读起来像被裁掉。

### 怎么验的

| 层 | 手段 | 结果 |
|---|---|---|
| 离线 | `bash adbprobe/test/run.sh` | `pass=27 fail=0`；断言含「默认面积固定 437760」「与 crop 等比 <0.5%」「严格居中」「全屏仍可用」「一键复位」「提示含安全件且不含任何帧率字样」 |
| 构建 | `SKIP_ICRECV=1 bash adbprobe/build.sh` | `BUILD OK` |
| 车机 | `192.168.31.210:5050`，图形安装器 | `lastUpdateTime=2026-11-05 16:18:33` |
| 复位 | `am start --es act reset` | 日志 `仪表区域已复位到安全区 352,180,1216,360`，输入框同步（`materials/CAP_safe.png`） |
| 超区警告 | `am start --es crect 0,72,1920,568` | 两行 ⚠ 提示完整显示、未被裁切（`materials/CAP_warn2.png`） |

### 还没做

1. **没起投屏**。本轮只核到「面板 / 日志 / 离线断言」三层。`recv 1216x360` 与 `DST 352,180 1216x360` 的实投确认需要**驻车**条件，待补。
2. **B5 硬门槛要跟着改口径**：基准从「按面积分档」改为「默认安全区下仪表 ≥58fps」，满宽横带不再作为门槛基准（已在进度表 §4 改）。
3. `AREA_SAFE` 仍是 50 万（只用来定默认方案），没有跟默认值 43.8 万拉齐 —— 有意为之：留出「用户微调一点仍在安全区」的空间，不必因为动了 20 像素就报警。

### 回滚

```bash
git revert <本批 commit>
```

只动 `adbprobe/src/com/xiaopeng/vdcast/{VdCtl,MainActivity,ClusterPickActivity}.java` 与测试，**不涉及** `asx/`、`tools/icrecv2/`、投屏协议、仪表端命令行签名。
想只回默认值不回文案：把 `Cfg` 的 `cCustom` 改回 `false` 即可（满宽横带分支一直保留着）；但注意 `MainActivity` 里那次 prefs 迁移会把旧默认值改成安全区，回滚时要一并撤销。

## B3 主面板与退出（2026-11-05 15:50）

> 本批是**面板可用性**的收口：把界面从上到下重排成「一屏一条主路径」，并给「退出」补上真正的收尾链路 —— 之前返回键直接 `finish()`，会留两个孤儿进程。安全相关，全部在驻车、未导航条件下验。

### 改了什么

| 文件 | 变更 |
|---|---|
| `MainActivity.java` | 见下 6 组（UI-04/06/08/10/11、UX-05/08/09/12） |
| `VdCtl.java` | ① 新增 `Health` 内部类 + `health()`：**一次 exec + 一次 Icrpc** 拿齐 adb / 发送端 / 接收端 / 两侧 fps / 虚拟屏数；② 新增 `startHealth()`，让 `start()` 顺手回一份零成本快照（启动后立刻有小灯，不用再等 336ms 探测）；③ 新增 `cleanupTemp()` 清 `/data/local/tmp/vdcap|_vdcrop.png|.adbprobe_*`（**不删** `vd.dex` 与 `/tmp/icrecv_dbg`，删了要重投 10–30s）；④ `stop(long deadlineAt)` 改为带截止时间，四步各自可判超时 |

**① UI-06 / UI-04：整屏重排 + 单一主 CTA**

从上到下：顶栏（标题 + 状态胶囊 + 右「退出」）→ 三层小灯行 + fps +「刷新」→ 流程条 → 卡片1 目标应用 → 卡片2 仪表区域 → 卡片3 主 CTA（全宽「启动投屏」+ 停止 + 面积提示 + 预览）→ 卡片4 高级（整宽 Button 标题，内含 `按 vd 启动` / `按 main 启动` / 连接 / 端口 / 状态 / 自检）→ 日志卡（默认折叠一行）。

端口、自检、`main/vd` 模式全部下沉进高级；原来散在界面的 8 个按钮只剩 1 个主 CTA。主 CTA 文案随状态走：

```java
if (busy || phase == Phase.STARTING) go = "正在启动…";
else if (phase == Phase.STOPPING) go = "正在收尾…";
else if (stale) go = "应用新配置";     // 投屏中改了需重启的参数
else if (fresh) go = "投屏中";         // 已投且配置一致，灰
else go = "启动投屏";
```

**② UI-10 / UX-08：三层小灯 + 用户级 4 态 + 工程级折叠**

```
灯：ADB 通 / 发送端 1 / 接收端 0 / 车机 62fps / 仪表 --fps（336ms）
```

三个 `dot` 分别对应 `dotAdb` / `dotSender` / `dotRecv`，`paintProcLamp()` 按进程数决定颜色：`≥1` 绿、`0` 红、探测失败灰。这是 B1 取消后「界面会说谎」的正解 —— 状态机只在动作时推进，小灯是**主动探测**，能把 adb 静默断开 / 发送端死了 / 接收端没起 这几种分开。

用户级 4 态（胶囊）：`就绪` / `投屏中` / `投屏中 · 配置已改`（琥珀）/ `出错了`（红）。工程级原始 `status()` 收进日志卡的诊断模式。

**③ UI-08 / UX-05：退出链路（本批最重要的一条）**

返回键原来直接 `finish()`，两个进程留在车机和仪表上。现在：

```java
@Override
public void onBackPressed() {
    if (!advOpen && !logOpen) confirmExit();          // 带收尾的退出
    else { if (advOpen) setAdvOpen(false); else setLogOpen(false); }  // 先收面板
}
```

`confirmExit()` → 弹「退出应用」二次确认 → `runExit()`，总预算 **8000ms**：

- 未投屏：直接关 socket + `finishAffinity()`，实测 **0ms**；
- 投屏中：串 `stopCasting()` 的 `[1/4]…[4/4]`，实测 **5040–5513ms**，预算内；
- 收尾不干净：弹「收尾未完成」，两个选项 ——「再收一次」/「强制退出」，**不许静默退出**；
- 干净：`finishAffinity()` 后 `postDelayed(Process.killProcess, 500)`，彻底退进程（实测退出后 `pidof` 返回 `rc=1`）。

**④ UX-12：restore 幂等 + 二次尝试 + 红色结论**

原来 stop 失败一次会「看起来成功」——两次踩坑已修：

1. `markConnected()` 不再覆盖 `ERROR`（只在 `IDLE` 时才肯进 `CONNECTED`），否则一次心跳探测就把红灯抹成「就绪」；
2. `receiverEverStarted` **只在 `restoreOk` 时**才清 —— 之前失败一次就清掉，第二次 stop 会跳过恢复验证，直接报「收尾完成」，红灯被抹掉。

判定仍以 `RESTORE_OK / pixels restored / RESTORED blank` 为唯一成功判据，返回值收紧为 `carOk && restoreOk`。

**⑤ UI-11：日志默认折叠一行，出错自动展开，诊断模式**

折叠态只显示一行摘要（`logSummary`），出错时摘要行变红并自动展开全文。展开态有「展开 / 清屏 / 诊断」三个按钮，行内 `setPadding(0,0,dp(110),0)` 给右下角车机悬浮球让出 110dp（车主说悬浮球可关，没关系，但不挡更好）。

诊断入口两条路并存：**连点日志卡标题 5 次**（`logTitleTaps >= 5` → `enableDiag()`，保留原设计），以及一个**显式「诊断」按钮**。后者是必须的 —— 实测注入 tap 在 `TextView` / 整行 `LinearLayout` 上收不到（只有 `Button` 稳定），PC 侧自动化没法靠「连点 5 次」进诊断。

**⑥ UX-09：首启 3 步引导**

首次启动弹一次，三条硬前提：① adb 授权要选「一律允许」；② 仪表必须开机；③ 必须驻车操作。用 `SharedPreferences` 记位，仅首启显示。

**⑦ 布局避让（B2 记的残点）**

- root 顶部 padding 改 `dp(30)`：顶部系统状态栏（铃铛 / 电量 / 时间）原来会压住顶栏整行，胶囊放哪儿都看不见；
- 日志行右侧 110dp 避让悬浮球（见 ⑤）；
- `setAdvOpen(true)` 后 `bodySv.fullScroll(View.FOCUS_DOWN)`：高级展开后内容在可视区外，不滚过去等于没展开。

**⑧ 遥控入口补齐（为 PC 侧与自动化）**

```java
case "health":  run(this::refreshHealth); break;
case "exit":    runOnUiThread(this::confirmExit); break;   // 人用的路径：带确认
case "exitnow": runOnUiThread(this::runExit); break;       // 自动化：无确认直接退
case "coords":  runOnUiThread(this::dumpCoords); break;    // 打印关键控件物理坐标
```

`coords` 是本轮踩坑的产物：`ViewImage` 返回的截图是缩放后的 1080×525，而 `input tap` 要物理坐标（2400×1200）。`dumpCoords()` 直接打 `getLocationOnScreen`，用于自校准。

**⑨ 顺手修的三条读数 bug**

| 症状 | 根因 | 修法 |
|---|---|---|
| `RECV=-1`，fps 串到车机那行 | icrpc 回包前会粘一段 protobuf 残渣（`+.)RECV=0`），`split` 位置全错 | 改 `indexOf("RECV=")` 子串匹配 + `numText()` 取数 |
| 接收端已死仍显示上一轮 fps | `frames=` 正则匹配到了退出时的 `stream idle/end: EOF (frames=826)` | 改 `grep -E 'frames=.*fps'`；进程数为 0 时 fps 直接置 `-1` |
| 「仪表未恢复」被误报成「进程未确认退出」 | `exitProblem()` 里两个错误用了同一条 `if` 链 | 改成 if/else，只报真正失败的那一项 |

### 怎么验的

驻车、未导航、全程可回滚。全部走遥控入口触发，日志与截图存 `docs/materials/`（不入库）。

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 首启引导 | 只弹一次，二启不弹 | ✓ 三条硬前提，`SharedPreferences` 记位生效 |
| 2 | 三层小灯（待机） | 三个灯各自独立 | ✓ `灯：ADB 通 / 发送端 1 / 接收端 0 / 车机 62fps / 仪表 --fps（336ms）`，一次探测 336ms |
| 3 | 三层小灯（投屏中） | 接收端 1，仪表有 fps | ✓ `.nudgeCarFps` 补采：`车机 62 · 仪表 55.6 fps` |
| 4 | 掉线感知 | 接收端灯变红并提示 | ✓ 手动杀掉仪表 `icrecv_dbg` 后，「接收端」灯转红，摘要行给出提示 |
| 5 | 主 CTA 单一性 | 主界面只有一个主 CTA | ✓ 端口/自检/`main vd` 全部下沉高级；闲置时只有「启动投屏」可点 |
| 6 | 停止四步 | `[1/4]`→`[4/4]`，结束给结论 | ✓ 全四步打印，`RESTORE_OK`，**`收尾完成，用时 4961 ms`** |
| 7 | 退出（未投屏） | ≤8s，进程彻底退出 | ✓ **0ms**；`finishAffinity()` + kill，`pidof` → `rc=1` |
| 8 | 退出（投屏中） | ≤8s | ✓ **5040 / 5289 / 5513ms**，预算 8s 内 |
| 9 | 收尾未确认恢复 | 弹「收尾未完成」，不许静默退 | ✓ 两个选项「再收一次 / 强制退出」；选「再收一次」后 **`RESTORE_OK`，907ms 自愈** |
| 10 | 错误态 | 胶囊红 + 日志自动展开 | ✓ `com.nonexistent.demo` → 胶囊「出错了」（红）、日志自动展开、摘要行红字 |
| 11 | 返回键 | 走确认框，不裸退 | ✓ 弹「退出应用」；取消后 app 仍在前台（pid 15359） |
| 12 | 退出后两端干净 | 无残留 | ✓ 车机无 VdSend、`残留虚拟屏: 0`、仪表无 `icrecv_dbg` |
| 13 | 悬浮球遮挡 | 日志行按钮不被盖 | ✓ 右侧 110dp 避让 |
| 14 | 状态栏压顶 | 顶栏整行可见 | ✓ root 顶部 `dp(30)` |

离线 `bash adbprobe/test/run.sh` → `RESULT OK`（14 项断言）；构建 `BUILD OK`。

### 还没做 / 已知残点

1. **UI-09 的「预演在启动前」仍未落地**：流程条现在是 `连接 → 选应用 → 仪表区域 → 裁切画面 → 启动投屏 → 预览`，静态预演属于 B4，届时**顺序要再挪一次**。这是 B2 就记下的账，本批没动。
2. **UX-04（启动自检四项 + 一键清理残留）/ UX-06（一键启动）/ UX-11（遥控语义统一）/ UI-12（进度条 + 可中断）** 仍未开始，仍是 B2 的遗留。本批只把「退出」这一侧的收尾做成闭环，「启动」那一侧的自检还是空的。
3. **小灯是拉取式，不是推送式**：`refreshHealth()` 要主动点「刷新」或等动作触发才更新，掉线不会自己亮灯。做自动轮询需要常驻定时器，会牵扯功耗与 B1 已取消的重连策略，留到 UX-06 一起考虑。
4. **`killvds stop` 的 4.35s 一点没动**：退出 5.0–5.5s 里绝大部分还是它，车主已确认在 B5 改轮询。
5. **诊断模式只改日志详细度**，没有做 adb 包级 trace。

### 回滚

```bash
git revert <本批 commit>
```

只动 `adbprobe/src/com/xiaopeng/vdcast/{MainActivity,VdCtl}.java`，**不涉及** `asx/`、`tools/icrecv2/`、投屏协议、仪表端 compose 命令行签名。若只想退「退出链路」这一条，把 `onBackPressed()` 改回直接 `finish()` 即可 —— 代价是恢复原状：留两个孤儿进程。

## B0 取证与基线（2026-11-05 14:30）

### 改了什么

**产品代码零改动。** 只加了一件测量工具和一份数据文档：

| 文件 | 变更 |
|---|---|
| `tools/bench/b0_coldstart.sh` | 新增。五轮冷启动采集：`am force-stop` → `am start -W` → 抓 `logcat -v threadtime -s ADBPROBE:I`。一次一轮跑（`bash tools/bench/b0_coldstart.sh 1 3` 表示第 3 轮），产物落 `docs/materials/b0/`（不入库） |
| `docs/B0-基线数据-v1.md` | 新增。11 项 PERF 基线 + 帧率对照 + 一个 bug 的完整证据链 |

### 怎么验的

驻车、未导航，同 APK（2.0 / versionCode 1）。4 次启停，每次收尾核对
`残留进程: 0` / `残留虚拟屏: 0` / 仪表 `RESTORE_OK`，全部通过；结束后两端 `ps` 无残留。

| 指标 | 基线 |
|---|---|
| PERF-01 冷启动→首帧 | 155–165ms，**中位 157 / P90 165** |
| PERF-03 连接 | 5–6ms |
| PERF-04 启动投屏 | **首次 8.24s / 二次 4.29s** |
| PERF-06 裁切热生效 | **310ms** |
| PERF-07 裁切页底图 | 截图 360ms + 起页 ≈200ms |
| PERF-09 应用列表 | **656ms**（36 个应用） |
| PERF-10 仪表 RPC | **1.5–1.9s**（应用内哨兵早退） |
| PERF-11 停止 | **4.97 / 4.99 / ≈5.0s** |
| M8 帧率 | 550×384 → **61.8fps**；1920×568 → **30.2→39.0fps** |

### 三个必须记住的结论

1. **60fps 是面积属性，不是链路属性**。仪表解码 ≈20–24ns/px，1.09M 像素的满宽横带物理上到不了 60。
   `docs/帧率优化记录.md` 的 58.6–59.9 是 550×384 的成绩，**不代表默认配置**。
2. **默认 `crect` 恰好是满宽横带**，所以开箱即用拿到的是 30–39fps。要 60 帧就得选小区域 —— 这是产品口径问题。
3. **本 ROM 不产生 `am_activity_launch_time`**（车机侧全缓冲区计数 0），以后别再指望 events 缓冲区。

### 测出来的 bug（已登记 UX-15，本轮不修）

自定义矩形 → 切回默认满宽横带 → 不重启应用直接启动：`outW/outH` 停在 550×384，
却往 1920×568 的带里画。仪表 `draw` 2ms→21.5ms、`total` 36.8ms、帧率 27fps、画面拉伸。
根因：`VdCtl.start()` 只在 `cCustom==true` 分支同步 `outW/outH`，没有回落分支。

### 没做 / 待做

- **PERF-05 换应用重启**：`killvds stop` 只恢复新 pkg 的窗口，旧应用可能留在已销毁虚拟屏上（实测不自动回收）。
  驻车也不该赌，等 B3 做换应用功能时连 restore 一起补。
- **PERF-08 预览首帧**：PreviewActivity 无埋点；本机也无 ffmpeg 做录屏抽帧。B4 加一行日志再测。
- **Q1/Q2 待车主决策**：60 帧目标按面积分档 vs 收窄默认矩形；`killvds stop` 的 4.35s 是否在 B5 动。

### 回滚

只删两个新文件即可，产品行为未变：

```bash
rm tools/bench/b0_coldstart.sh docs/B0-基线数据-v1.md
```

## B2 状态机与流程骨架（2026-11-05 14:55）

### 改了什么

| 文件 | 变更 |
|---|---|
| `MainActivity.java` | ① 新增 `Phase` 枚举（IDLE/CONNECTED/STARTING/CASTING/STOPPING/ERROR）+ `launchedKey` 配置快照 + `previewOpened`，**所有界面状态由 `refreshUi()` 一次推导**；② 删掉 `step(i, true)` 手工打勾与 `setState()`，按钮可用性也并进 `refreshUiNow()`；③ 流程条与卡片顺序改为「选应用 → 仪表区域 → 裁切画面 → 启动投屏 → 预览」；④ 三个输入框加 `TextWatcher`，改了参数立刻重算；⑤ 状态胶囊从标题最右移到标题右侧（原来被系统状态栏压住）；⑥ 未投屏时点预览直接提示原因，不再跳一个只会重试的黑屏 |
| `VdCtl.java` | ① 新增 `Cfg.normalize()`：矩形与发送尺寸统一对齐，**满宽横带分支显式回落 `1920 x (720-top-bot)`**（UX-15 根因）；② 新增 `Cfg.areaPx()` / `isFullBand()` / `fpsHint()`，`[0/8]` 打印「投屏区 550x384，21 万像素，可跑满 60 帧」；③ 新增 `activePkg` 快照，`stop()` / `status()` 用**投出去的那一份**，不再用界面上可能已改过的 `cfg.pkg`；④ `stop()` 收尾后把面板自身拉回前台 |

### 怎么验的

驻车、未导航，全部走遥控入口（`am start --es act …`），逐状态截图存 `docs/materials/B2_*.png`。

| 场景 | 期望 | 实测 |
|---|---|---|
| 冷启待机 | 只有配置类步骤打勾，连接/启动/预览未完成 | ✓ 1 连接为当前步，2–4 绿勾，5/6 灰 |
| 点连接 | 胶囊「已连接」（绿），步骤 1 打勾 | ✓ 5–6ms |
| 启动投屏 | 胶囊「投屏中」，步骤 5 打勾，6 变当前步 | ✓ 550×384 58.0fps |
| 投屏中换应用 | 胶囊「投屏中 · 配置已改」（琥珀），步骤 5 **变回未完成** | ✓ 这就是 UI-07 的原症状，现已消失 |
| 停止 | `RESTORE_OK`，胶囊回「已连接」，面板自动回前台 | ✓ `topResumedActivity` 回到 `com.xiaopeng.vdcast/.MainActivity`，两端残留 0 |
| 未投屏点预览 | 只打日志说明原因，不跳页 | ✓ 「预览画面来自发送端的实时流（端口 5570），先启动再看」 |
| **UX-15 双向** | 550×384 → 1920×568 → 550×384 每次都打印对应尺寸 | ✓ 57.6 / 27.4 / 57.6 fps，`recv` 尺寸与投屏区一致，无拉伸 |

离线协议测试 `test/run.sh` 14 项仍全过；构建 `BUILD OK`。

### 还没做

1. **`UI-09` 只做了一半**：顺序已改，但「预演在启动前」依赖 B4 的静态预演（`fb0_pre.png` 打底 + `crect` 摆位），届时流程条要再挪一次。
2. **`UX-04` 启动自检 / `UX-06` 一键启动 / `UX-11` 遥控语义统一 / `UI-12` 可中断**：仍归后续批次，B2 只收敛了状态骨架。
3. **布局残点交给 B3**：「车机画面裁切」行的「应用」按钮仍会被车机悬浮球挡住，需要在主面板重排时做右侧避让。
4. **掉线不会被状态机感知**：`Phase` 只在动作时推进，`adb` 静默断开后仍显示「投屏中」。这是 B1 取消的已知代价，B3 的「三层小灯」（UI-10）是正解。

### 回滚

```bash
git revert <本批 commit>
```

只动 `adbprobe/src/com/xiaopeng/vdcast/{MainActivity,VdCtl}.java`，不涉及 `asx/`、`tools/icrecv2/`、协议与命令行签名。

## B0.5 品牌与包名改造（2026-11-05）

### 改了什么

| 文件 | 变更 |
|---|---|
| `adbprobe/AndroidManifest.xml` | `package=com.xiaopeng.vdcast`、`android:label="仪表投屏"`、`android:icon="@mipmap/ic_launcher"`、`versionCode=1` / `versionName=2.0` |
| `adbprobe/src/com/probe/adbprobe/` → `src/com/xiaopeng/vdcast/` | 8 个类整包迁移（`git mv`，保留历史），`package` 声明同步 |
| `adbprobe/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 新增，取自 `docs/materials/icon/res`（未使用 adaptive-icon） |
| `adbprobe/build.sh` | 增加 `aapt2 compile --dir res` + link `-R build/res.zip`；`asx` 产物缺失时沿用 `assets/vd.dex` 并告警；新增 `SKIP_ICRECV=1` 跳过仪表端交叉编译 |
| `adbprobe/deploy.sh` | `PKG=com.xiaopeng.vdcast` |
| `adbprobe/test/run.sh`、`MockAdbdTest`、`RealAdbTest` | 包路径/import 同步；补 `test/android/util/Base64.java` 离线替身（此前缺失，`run.sh` 根本跑不起来） |
| `adbprobe/src/.../VdCtl.java` | `SELF` 改为新包名（选应用列表排除自身） |
| `README.md` | 新包名、安装命令、构建说明；新增「adbd 授权单槽位 / 密钥导入」运维条目 |
| `docs/APK控制器-v0.2.md` | 顶部加迁移说明，历史正文不回改 |

### 两处必要的额外改动（原计划在 B1，但会直接伤车，提前做）

1. **密钥导入**：换包名后应用私有目录是新的，`adbkey.der` 不存在 → 会生成新 key → 走本 ROM 的「单槽位授权」。槽位里没有残留 pending 时，这次授权能正常走完（12:58 实测）；**有残留 pending 时会一直超时**，处理办法是开发者工具里「无线 ADB」关开一次复位——可恢复，不是永久损坏（13:20 修正）。
   现在 `ensureConn()` 在生成之前先尝试导入 `/data/local/tmp/vdcast_adbkey.der` 或 `/sdcard/Download/vdcast_adbkey.der`（PKCS#8 DER，解析通过才写入）。
2. **公钥只提交一次**（ADB-12 的最小实现）：`Adb.pubkeySubmitted` 静态位，提交过就放弃重试，不再每轮握手重复 `AUTH_RSAPUBLICKEY`。

### 怎么验的

| 项 | 结果 |
|---|---|
| 构建 | `bash adbprobe/build.sh` → `BUILD OK`，v2+v3 签名通过 |
| 包元数据 | `aapt2 dump badging`：`package='com.xiaopeng.vdcast' versionCode='1' versionName='2.0'`，`application-label:'仪表投屏'`，5 档 mipmap 全部存在，launcher activity = `com.xiaopeng.vdcast.MainActivity` |
| 离线协议测试 | `bash adbprobe/test/run.sh` → 14 项断言 `RESULT OK`（Icrpc 帧 hex 与之前一致） |
| 安装 | `tools/installapk.sh` 图形安装器一轮成功，`lastUpdateTime=2026-11-05 12:44:38` |
| 启动 + 连接 | `logcat`：`仪表投屏 v2.0` → `已导入已有密钥…`（首次）→ `token 20B 用 raw 签名` → `已连接`，耗时 5–6ms |
| 授权槽位未被占用 | `logcat -b all \| grep -c 'prompting user\|prompt currently pending'` = **0** |
| 仪表端二进制一致性 | `assets/icrecv_dbg` / `tools/icrecv2/icrecv_dbg` / APK 内副本 md5 均为 `999cd1858c0dd2aa3239b7809af8f87f`（与审计基线一致，不会触发重投） |

### 没做 / 待做（需要驻车或人工确认）

- **前缀保活 30 分钟对照** —— **2026-11-05 13:10 暂缓**（车主明确「保活先不用操心」）。做法留档：新包与旧包各跑一次，`pidof com.xiaopeng.vdcast` / `dumpsys activity processes` 对比存活；旧包已卸载，若重启此验证需另装一份对照包。
- **launcher 应用列表观感**（图标 48–96px 是否清晰）：已于 12:53 实拍确认，`BRAND_v2_applist_real.png`，本条已完成。
- **旧包卸载** —— **已完成（2026-11-05 13:0x，车主手动执行）**。车上只剩 `com.xiaopeng.vdcast`；桌面 dock 可能留空位，需手动替换图标。附带结论：以后升级只能**覆盖安装**，`pm uninstall` 会连私有目录的 `adbkey.der` 一起删（见下方「一个必须纠正的认知」）。
- **B0 基线**：毫秒时间戳已加（`HH:mm:ss.SSS`），但 PERF-01…11 的数值尚未采（车机在导航，起投屏会占仪表）。

### 一个必须纠正的认知（2026-11-05 13:10 补记）

v2.0 首次启动日志里的「**已导入已有密钥**」很容易被读成「应用自己找到并沿用了旧 key」。**不是。**

真实过程是：装机前由维护者把旧 `adbkey.der` 复制到 `/data/local/tmp/vdcast_adbkey.der`，应用启动时 `importKeyIfPresent()` 在约定路径读到它并落到私有目录，才没有生成新 key。

因此：

1. 「零授权」验证的是**投放流程有效**，不是「应用已解决授权问题」；
2. 换包名/重装后首次连接本来就要走一次新 key 授权；想省掉这一步，才需要**手动投放 key**（人做的，代码只是兜底）。不投放也能用，代价是等一次授权；
3. 由此得到两条注意项（**13:20 已从「红线」降级**）：升级优先**覆盖安装**，省掉一次重新授权；真要改包名/重装也能做，代价是首次连接多一次新 key 授权，卡住就关开无线 ADB 复位；
4. ~~当前已授权私钥尚无本地备份~~ —— 13:20 车主明确「key 没关系，能触发授权就行」，不再作为待办。重装丢 key 的代价仅为重新授权一次；`.gitignore` 仍保留 `*.der`，私钥永不入库。


### 回滚

```bash
git revert <本批提交>            # 代码
bash tools/installapk.sh docs/materials/adbprobe-baseline.apk com.probe.adbprobe adbprobe.apk   # 装回旧包
```

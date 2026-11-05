# 改造记录（按批）

> 与 `docs/审计-四类改造-答复与需求确认-v1.md` 的批次一一对应。
> 每条记录「改了什么 / 怎么验的 / 还有什么没做」，便于回滚与交接。

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

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

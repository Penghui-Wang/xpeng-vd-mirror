# 遥控入口 `act=` 语义（v1，UX-11 定稿）

> 本文是 `act=` 的**唯一说明**。三处同源，改一处必须三处一起改：
>
> | 位置 | 作用 | 怎么保证不漂 |
> |---|---|---|
> | `adbprobe/src/com/xiaopeng/vdcast/Acts.java` | APK 侧动作表 | 唯一真相源 |
> | `adbprobe/src/com/xiaopeng/vdcast/MainActivity.java` | `handleIntent` 的 switch | 离线断言逐条核对 `case "<动作>":` |
> | `tools/vdcast.sh` | PC 侧驱动脚本 | 离线断言核对 `ACTS=` 行；脚本自带 `selftest` |
>
> 校验命令：`bash adbprobe/test/run.sh`（其中的 `ActsTest`）+ `bash tools/vdcast.sh selftest`。

---

## 1. 五条规则（改之前先读）

1. **参数在前、动作最后。**
   `am start` 会把 `--es` 之后多出来的裸参数当成 data URI。
   ```bash
   # 错：1,2,3,4 被当 data URI 吃掉，应用只收到 act=crop、没有 crop
   adb shell am start -n com.xiaopeng.vdcast/.MainActivity --es act crop 1,2,3,4
   # 对：
   adb shell am start -n com.xiaopeng.vdcast/.MainActivity --es crop 1,2,3,4 --es act crop
   ```
   这就是 B0 基线 §3.3 记下的坑。**用 `tools/vdcast.sh` 时这个顺序由脚本保证**，不可能写错。
   APK 侧也做了兜底：真收到 data URI 时日志会明说「参数被 am 当 data URI 吃掉了」。

2. **参数只属于声明消费它的动作。**
   `act=status` 不会顺手把 `--es pkg` 写进配置（老版本会，PC 侧根本看不出副作用）。
   带了不消费的参数 → 日志里标 `[忽略]`；PC 脚本则直接**拒绝执行**。

3. **必需参数缺失 = 拒绝执行**，绝不沿用界面上的旧值。
   老版本 `act=crop` 没带坐标时会拿输入框里的旧裁剪值继续跑，日志还写「裁切已设为 …」——
   看着像成功。现在直接报 `!! act=crop 缺 --es crop`。

4. **旧名不再接受，但会告诉你改成什么**（R3-2 不要求兼容，但不许让人猜）。
   见 §4 迁移表。PC 脚本遇到旧名直接退出码 2。

5. **只带参数、不带 `act` = 什么都不做。**
   老版本会静默改界面字段，看不出来没生效。

---

## 2. 动作表（22 条）

| `act` | 参数 | 作用 | 等价界面 |
|---|---|---|---|
| `connect` | `--es port`（可选） | 只连 adbd | 高级 → 连接 |
| `status` | — | 打印现状（工程级 `status()`） | 高级 → 状态 |
| `health` | — | 刷新三层小灯 + 两侧帧率 | 小灯行 → 刷新 |
| `probe` | — | 环境四步自检 | 高级 → 自检 |
| `pick` | — | 选目标应用 | 目标应用 → 选择 |
| `apps` | — | 候选应用清单写日志（诊断） | 仅遥控 |
| `area` | — | 打开仪表区域框选页 | 仪表区域 → 框选 |
| `snap` | — | 打开车机画面裁切页 | 车机画面 → 裁切 |
| `crop` | `--es crop x,y,w,h` **必需** | 应用裁切（热生效） | 车机画面 → 应用 |
| `crect` | `--es crect x,y,w,h` **必需** | 应用仪表矩形（需重新启动才生效） | 仪表区域 → 应用 |
| `start` | `--es pkg` **必需**；`--es mode vd\|main`；`--es crop`；`--es crect` | 一键启动（8 步） | 主按钮「启动投屏」 |
| `stop` | — | 停止并复原（4 步） | 主按钮「停止」 |
| `cancel` | — | 中断当前任务（粒度＝步） | 进度条 → 中断 |
| `residue` | — | 重新做启动自检 | 残留横幅 |
| `clean` | — | 一键清理残留 | 残留横幅 → 清理 |
| `preview` | — | 打开实时预览 | 「预览」 |
| `reset` | — | 仪表区域复位到安全区 | 仪表区域 → 复位 |
| `coords` | — | 控件坐标写日志（自动化自校准） | 仅遥控 |
| `exit` | — | 退出（带二次确认弹窗） | 顶栏 → 退出 |
| `exitnow` | — | 退出（不确认） | 仅遥控 |
| `resync` | — | 清工具同步标记，下次启动重推 | 仅遥控 |
| `help` | — | 把本表打进日志 | 仅遥控 |

`--es mode` 只认 `vd` / `main`，缺省 `vd`（与主 CTA 的默认一致）。
`--es port` / `--es pkg` 等值都会先写进界面再执行动作，所以 `start` 可以一条命令带齐参数。

---

## 3. PC 侧用法

```bash
bash tools/vdcast.sh table                     # 看全表
bash tools/vdcast.sh status                    # 现状
bash tools/vdcast.sh help                      # 让 APK 把动作表打进 logcat

bash tools/vdcast.sh crect --crect 352,180,1216,360
bash tools/vdcast.sh start --pkg com.tencent.qqmusicpad --mode vd \
     --crop 960,345,1440,426 --crect 352,180,1216,360 --wait 就绪 --timeout 40
bash tools/vdcast.sh stop --wait RESTORE_OK --timeout 12

bash tools/vdcast.sh log 40 start              # 回读 ADBPROBE 日志（取证）
bash tools/vdcast.sh start --pkg x --dry-run   # 只看拼出来的命令，不执行（选项写在动作之后）
bash tools/vdcast.sh selftest                  # 离线自检，不需要车机
```

`--wait <文本>` 的做法是「发命令前后各数一次 logcat 里该文本的出现次数，变多即成功」，
所以不会把上一轮的旧日志当成这一轮的结果。

---

## 4. 迁移表（旧名已删除）

| 旧写法 | 现在怎么写 | 说明 |
|---|---|---|
| `--es act vd` | `--es act start --es mode vd` | `vd` 是模式不是动作 |
| `--es act main` | `--es act start --es mode main` | 同上 |
| `--es act cluster` | `--es act area` | 与界面「仪表区域」对齐 |
| `--es pkg X` 不带 `act` | 必须带 `act` | 参数不再单独生效 |

> 删除这三个动作是 R3-2「不要求向后兼容」的直接结果；按答复件 §2.3 要求，
> 同一批里 **APK 与 PC 脚本一起改**，并在 CHANGELOG 写明。

---

## 5. 怎么验证（三层层层收口）

| 层 | 手段 | 期望 |
|---|---|---|
| 表 vs 代码 vs 脚本 | `bash adbprobe/test/run.sh` | `ActsTest` 44 条全过（含 `MainActivity` switch 覆盖、PC 脚本动作集合一致） |
| 命令拼装 | `bash tools/vdcast.sh selftest` | `pass=71 fail=0`；每条动作都以 `--es act` 结尾、act 之后无裸 token |
| 车机 | `bash tools/vdcast.sh log 40 遥控` | 每条动作一行 `遥控 → <动作> [参数]`；打错顺序能看到「被 am 当 data URI 吃掉了」 |

车机侧的负例（故意写错，用来证明兜底有效）：

```bash
adb shell am start -n com.xiaopeng.vdcast/.MainActivity --es act crop 1,2,3,4
# 期望日志（实测 2026-11-05 17:20）：
#   !! 参数写在了 --es act 之后，被 am 当成 包名：1,2,3,4
#      参数写在前、动作写最后：--es crop 1,2,3,4 --es act crop
#   遥控 → crop
#   !! act=crop 缺 --es crop（x,y,w,h）
#      参数写在前、动作写最后：--es crop 1,2,3,4 --es act crop
```

> 为什么是「包名」而不是「data URI」：纯坐标 `1,2,3,4` 没有 scheme，am 把它落成
> `Intent { … pkg=1,2,3,4 … }`；带 scheme 的值（`file:///…`）才落成 `dat=`。
> 两种情况 APK 都会识别并点出来。

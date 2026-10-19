# 用模拟器调试（不插线）

USB 调试的替代方案。目标不是"模拟器更真"，而是**可复现、可脚本化、可截图**：布局、抽屉手势、轮次索引、通知、断线重连都能在模拟器上跑，而且它还能验一件真机验不了的事——`pm revoke` 通知权限（见文末能力边界）。

## 已验证可用的配置（2026-10-19）

| 项 | 值 |
|---|---|
| 模拟器 | Android emulator **37.2.12**（`$ANDROID_SDK_ROOT\emulator`） |
| 系统镜像 | `system-images;android-35;google_apis;x86_64`（userdebug，**可 adb root / pm grant**） |
| 硬件加速 | WHPX 可用（`emulator -accel-check`） |
| AVD | `mizar35`，pixel_6 配置（1080×2400 @420dpi，和 OnePlus 12 接近） |
| 渲染 | **`-gpu host`**（RTX 4060）——`swiftshader_indirect` 在 WHPX 下会**启动挂死**（今天实测两次：qemu CPU 不再增长、adb offline） |
| 内存 | 4096 MB（`-memory`，注意不是 `-ram`） |
| 数据分区 | 持久化（`config.ini` 里删掉 `disk.dataPartition.path=<temp>`），配对与安装的 App 跨重启保留 |

启动命令（脚本已封装）：

```powershell
pwsh scripts/emulator/emu.ps1 boot
```

等价于：

```
emulator -avd mizar35 -no-window -no-audio -no-boot-anim ^
         -gpu host -memory 4096 -port 5556 -no-snapshot -no-metrics
```

首次冷启动约 1–2 分钟。**在 DSH 工具里调用时必须后台运行**（`run_in_background`）：工具调用结束会回收进程树，直接 `Start-Process` 起的模拟器会跟着被杀——今天就是这么"看起来挂了"的。普通终端里 `./emu.ps1 start` 的分离启动没问题。

## 一次性准备

```powershell
# 1. 建 AVD（会同时把 config.ini 调成 host GPU / 4 GB / 持久化数据）
$env:JAVA_HOME = '<jdk>'
pwsh scripts/emulator/emu.ps1 create

# 2. 启动并等待（工具里：后台任务跑 boot，再另起一条命令 wait）
pwsh scripts/emulator/emu.ps1 wait
```

## 装包与配对

```powershell
pwsh scripts/emulator/emu.ps1 install           # 装 :app:assembleRelease 的产物
pwsh scripts/emulator/emu.ps1 grant             # 模拟器可 adb grant 通知权限
pwsh scripts/emulator/emu.ps1 locale            # 切成 zh-CN（重启框架约 1 分钟）
pwsh scripts/emulator/emu.ps1 pair ZH8M-NVT0    # 配对码来自 DSH-Mizar 面板
```

配对**不用摄像头、不走公网中继**：模拟器里的 `10.0.2.2` 是宿主 loopback 的别名，正好命中插件监听的 `127.0.0.1:19390`。`pair` 子命令就是发一条深链：

```
mizar://pair?h=10.0.2.2:19390&c=<配对码>&n=Acer-emu&v=1
```

配对码一次性、5 分钟有效、来自 DSH 侧边栏的 DSH-Mizar 面板（只有宿主操作员能生成）。数据分区持久化之后，**一个 AVD 只需配对一次**。

备用通路：`adb -s emulator-5556 reverse tcp:19390 tcp:19390`，然后用 `h=127.0.0.1:19390`。

## 日常操作

```powershell
pwsh scripts/emulator/emu.ps1 status                 # 设备/boot/已装版本
pwsh scripts/emulator/emu.ps1 shot after-pair        # → .devstate/emu-after-pair.png
pwsh scripts/emulator/emu.ps1 swipe 250 800 540 800 500   # 打开侧边栏（0.20 阈值）
pwsh scripts/emulator/emu.ps1 swipe 700 1200 520 1200 500 # 关闭侧边栏（0.15 阈值）
pwsh scripts/emulator/emu.ps1 tap 75 205             # 菜单按钮
pwsh scripts/emulator/emu.ps1 logcat mizar-notify
pwsh scripts/emulator/emu.ps1 stop                   # 关机并清掉残留 lock
```

坐标系与真机一致（1080×2400 设备像素）：模拟器 `screencap` 出的图就是真机截图坐标，不需要换算。

## 能力边界

| 验收项 | 模拟器 | 说明 |
|---|---|---|
| B1–B12：搜索框、抽屉手势、轮次索引、连接面板等 | ✅ | `input swipe/tap` + `screencap` 全自动 |
| C1 冷启动恢复 | ✅ | |
| C2 后台完成通知 | ✅ | google_apis 有完整通知栈 |
| C3 前台抑制 | ✅ | |
| C4 断线横幅与重连 | ✅ | `adb shell cmd connectivity airplane-mode` 可用 |
| **C5 通知权限闸门** | ✅ **只有模拟器能验** | userdebug 镜像允许 `pm revoke` / `pm grant`；真机 ColorOS 直接拒绝 shell 改权限 |
| 扫码配对 | ⚠️ | 用深链跳过摄像头 |
| FCM 推送 | ⚠️ | `google_apis` 无 Play 商店；要验推送需 `google_apis_playstore` 镜像 |
| ColorOS 冻结、关通知权限杀前台服务 | ❌ | 只有真机有（真机上 C5 不可达的原因） |

## 故障阶梯

1. 启动后 `adb devices` 有 `emulator-5556` 但 `shell` 超时、qemu CPU 不再增长 → **换了渲染方式**：确认用的是 `-gpu host`。
2. 仍挂 → `./emu.ps1 stop`（清 lock）后重试；再不行加 `-feature -Vulkan` 或改用 `system-images;android-34;google_apis;x86_64` 重建 AVD。
3. `unknown option: -ram` → 内存参数是 `-memory`。
4. 配对失败：先 `adb -s emulator-5556 logcat -s mizar-notify` 看是 `PAIRING_CODE_*` 还是连不上；连不上时确认宿主 `Get-NetTCPConnection -LocalPort 19390 -State Listen` 里有 `127.0.0.1`。

## 已验证的事实（2026-10-19）

- 冷启动成功 → `sys.boot_completed=1`
- `adb root` 可用（userdebug）、`pm grant POST_NOTIFICATIONS` 成功
- 切 `zh-CN` 后界面为中文（"还没有配对任何电脑 / 扫描二维码 / 或手动输入"）
- 深链配对成功，侧边栏显示真实宿主的工作区与"已连接 Acer"，走的是直连而非公网中继

# TXHook

> QQ（Android）SSO 协议抓包分析工具 —— 基于 Xposed / LSPosed 的模块。

本项目是 [callng/QQHook](https://github.com/callng/QQHook)（原作者 [fuqiuluo](https://github.com/fuqiuluo)）的**fork**。

---

## 与上游的差异

fork 之后做了一次整体重构，抓包对象从「TEA / TLV / MD5 / 完整包」收敛为 **SSO 收发包**，
交互与展示重做，并修掉了一批解析上的错误：

- **界面重写**：Compose + Material 3，参考 Reqable 的交互；按 `seq` 把同一次请求的
  发送包与回包合并成「一次交互」卡片，显示耗时、大小与状态。
- **协议自动识别**：protobuf / JCE / TRPC 信封 / 纯文本 / gzip 自动判定，不再需要手动选类型。
- **长度前缀自动探测**：不硬编码宽度，枚举 1 / 2 / 4 字节 × 大小端，只有数值与包长自洽
  且**解析成功**才采用该候选。
- **解析器重写**：修正 JCE 语义错误（STRUCT 包裹、MAP、LIST、类型宽度），
  protobuf 侧补齐嵌套消息与 tag 扩展；两者都能解时按结构特征打分择优。
- **详情页 JSON 树**：结构视图输出 `{tag: value}` 形式，嵌套自动展开、可折叠；
  复制 / 分享直接得到标准 JSON，未解析的包退回 hex，不丢数据。
- **文件投递通道**：MIUI 等 ROM 会拦截 QQ 进程唤醒本应用的 ContentProvider
  （WakePathChecker），导致数据全丢。改为 hook 端写 QQ 自己的 `files/qqhook_inbox/`
  （JSON Lines），App 端用 root + `nsenter` 进入 QQ 的 mount namespace 轮询读取。
  未受限的设备仍保留 ContentProvider 通道。
- **历史会话**：一次抓包 = 一个会话，落盘为 `files/captures/<开始时间戳>.json`，
  可在「历史」页回看；浏览历史时新包不会混入，也不会覆盖原始记录。
- **悬浮控制条**：在 QQ 之上显示开始 / 暂停，可拖动。
- **导出**：列表长按多选，导出到 `Download/QQHook/` 并拉起系统分享面板；
  写文件失败时退回剪贴板。
- **单元测试**：解析器、收发合并等纯逻辑有覆盖（`app/src/test`）。
- **CI**：推送 `v*` 标签自动构建并发布 Release，也可手动触发。

---

## 功能

- 抓取 QQ 的 SSO 收发包（`send` / `receive`），记录 `cmd`、`seq`、`uin`、`msgCookie`、原始 buffer
- 较新版本 QQ 通过 MSF 内核层（`MSFKernel`）hook 收发；旧版本走 `CodecWarpper`
- 抓包列表支持搜索（`cmd` / `seq` / `uin`）、左滑删除、长按多选
- 详情页左右对照「发送 / 接收」，结构树与 HEX 双视图

> 上游的 HTTP 推送通道代码仍在（`PrefsManager.KEY_PUSH_API`），但设置界面已移除入口，
> 默认不会向任何服务端发送数据。

模块作用域：`com.tencent.mobileqq`、`com.tencent.tim`、`com.tencent.qqlite`。

---

## 使用要求

| 项目 | 要求 |
| --- | --- |
| 模块框架 | LSPosed（Xposed API 82） |
| 系统 | Android 8.1+（`minSdk 27`） |
| 架构 | arm64-v8a |
| root | **读取数据需要 root**（文件投递通道依赖 `su` + `nsenter`） |

> 没有 root 时，只有未被 ROM 拦截 ContentProvider 的设备还能收到数据；
> 大多数国产 ROM（MIUI / HyperOS 等）会拦截，此时列表为空。

### 使用流程

1. 在 LSPosed 中启用 TXHook，作用域勾选 QQ，然后**强制停止并重启 QQ**。
2. 打开 TXHook 本体（**不运行模块本体收不到任何数据**），同意提示后进入主界面。
3. 点右下角开始抓包，再回到 QQ 正常使用，包会实时出现在列表里。
4. 点卡片看详情；长按进入多选，可批量导出。
5. 暂停抓包时当前会话自动落盘到「历史」。

> 不抓包时请关闭模块或暂停抓包，hook 常驻会带来一定性能开销。

---

## 构建

依赖 JDK 21、Android SDK Platform 37、Build-Tools 36.1.0。

```bash
git clone --recursive https://github.com/weq-devs/qqhook
cd qqhook

# 子模块 libs/qqstub 提供 QQ 侧的编译期 stub（compileOnly，不会打进 APK）
# 如果克隆时漏了 --recursive：
git submodule update --init --recursive

./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。

Release 签名通过环境变量提供，缺省则构建未签名的 APK：

```bash
export KEYSTORE_PATH=../keystore.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

版本号由 git 推导：`v3.3.0-<提交数>-<短 hash>`，`versionCode` 即提交数。

跑单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

---

## 已知限制

- 只展示 SSO 收发；TEA / TLV / MD5 等旧 action 已不再抓取与展示。
- QQ 版本适配随官方更新变化，不保证所有版本可用；新版本可能只能拿到内层包数据。
- 历史记录里单条 buffer 超过 2 MiB 会被截断，避免大文件包撑爆存储。
- 无 root 时功能严重受限（见上文）。

---

## 致谢

- [fuqiuluo](https://github.com/fuqiuluo)：项目原作者
- [callng](https://github.com/callng) / [owo233](https://github.com/owo233)：上游维护者
- [EzXHelper](https://github.com/KyuubiRan/EzXHelper)：Xposed 辅助库

---

## 许可证与免责声明

本项目沿用上游的 **GPL-3.0** 许可证，详见 [LICENSE](LICENSE)。
修改与分发需同样以 GPL-3.0 开源并保留原作者版权声明。

本工具仅供**学习与研究**使用。抓包数据可能包含账号、消息等个人隐私，
请勿传播或用于任何非法用途；使用需遵守当地法律法规。因使用本工具产生的一切后果由使用者自行承担。

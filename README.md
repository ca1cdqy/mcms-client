# MCMS Mod (Architectury)

在游戏内深度接入 [MCMS 城市管理系统](https://mcms.flynt.hk/) 的客户端模组。

- 加载器：Fabric（Architectury 多平台结构，common 代码可扩展至 NeoForge/Forge）
- 目标版本：Minecraft 1.21.11（Java 21）
- 依赖：Architectury API 19.0.1、Fabric API 0.141.6+1.21.11、Fabric Loader 0.18.4

## 模块

- `common`：平台无关代码（REST/WS 客户端、IRC 群组系统、命令、配置、DTO）
- `fabric`：Fabric 平台入口、线程安全输出、命令补全与点击切群 mixin

## 构建与运行

```powershell
# 本机 JDK21 的 cacerts 不完整 + 网络有 MITM 代理，需要 truststore（已配好）
.\run-client.ps1          # 启动开发客户端
.\gradlew.bat :fabric:build
```

产物：`fabric/build/libs/mcms-0.1.0.jar`

## 游戏内命令（前缀默认 "."，可 .setprefix 修改）

| 命令 | 说明 |
| --- | --- |
| `.login <账号> <密码>` | 登录 MCMS，密码明文存入 `config/mcms.json`，之后自动登录 |
| `.logout` | 退出登录并清除本地凭证 |
| `.status` | 显示登录/WS/当前群组状态 |
| `.irc <文字>` | 向当前群组发消息；未选择群组时发到最近活跃群组 |
| `.irc list` | 列出我的群组（名称 + 群号） |
| `.irc set <群号>` | 切换当前群组（Tab 补全群号） |
| `.irc create <名称>` | 创建群组 |
| `.irc join <群号>` | 按群号加入群组 |
| `.irc quit` | 退出当前群组 |
| `.setprefix <前缀>` | 修改命令前缀（空串 = 禁用命令） |

交互：
- 聊天框输入 `.` 会自动弹出本模组命令补全
- 其他群组的消息可**左键点击**（打开聊天框 T 后点击）切换为当前群组

## API 逆向笔记

REST base `https://mcms.flynt.hk/api`，鉴权头 `token: <token>`；
WebSocket `wss://mcms.flynt.hk/api`（JSON 帧 `{key, value, timestamp, msgId}`）。
完整端点与协议见 `recon/` 下的前端 bundle 分析。
## 开发热重载

开发客户端使用 Architectury 自带的开发热重载（dev transformer 监听 `mcms-0.1.0-dev.jar` 变化）：

1. `.\run-client.ps1` 启动开发客户端
2. 修改代码后运行 `.\gradlew.bat :fabric:build`
3. 运行中的游戏会自动应用**方法体**改动（无需重启）

限制：不能新增/删除方法、字段、类，mixin 改动需要重启客户端。
（注：曾尝试 HotSwapAgent，与 Architectury 的开发热重载冲突，已移除。）
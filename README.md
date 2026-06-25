<div align="center">
    <h1>QAFUN</h1>

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=android&logoColor=white)
![TypeScript](https://img.shields.io/badge/Plugin-TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white)
![QuickJS](https://img.shields.io/badge/Engine-QuickJS--kt-00D9C0?style=flat-square)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)

</div>

# 简介

**QAFUN** 是一款基于 Xposed 框架开发的 QQ/TIM 功能增强模块。
在 QFun 原项目基础上进行了深度重构，采用 **Kotlin + Jetpack Compose** 现代技术栈，并将第三方插件引擎从 BeanShell 完全重写为 **TypeScript (QuickJS-kt)**，新增自定义桌面壁纸、防检测等特性。

主题色：**紫色 #7C5CFF + 青色 #00D9C0** 渐变。

# 核心特性

## 🆕 本次新增（相比 QFun 原版）

### 1. TypeScript 插件引擎（完全重写）
- 用 **QuickJS-kt** (`io.github.dokar3:quickjs-kt-android:1.0.5`) 替换 BeanShell 解释器
- 自研 **TsTranspiler**：轻量级 char 状态机，将 TypeScript 转译为 JS（剥离类型注解、interface、enum、访问修饰符、as/satisfies/! 断言）
- `PluginCompiler` / `PluginBridge` / `PluginCallback` 全部重写
- **60+ 绑定 API**：`log`、`toast`、`sendMsg`、`sendPic`、`sendPtt`、`getGroupList`、`renderTextImage`、`setTimeout`、`console` 等
- 事件监听：收发消息、群成员变动、拍一拍、聊天界面切换、菜单项点击
- 真实异步定时器（`ScheduledExecutorService` 回调进 QuickJS）
- Mutex 序列化执行，线程安全

### 2. 图片外显插件（TS 示例）
- 6 套渐变预设（紫梦/海洋/日落/森林/暗夜/烈焰）
- 触发词模式 + 自动模式
- 文字渲染为精美渐变图片发送
- 宿主端 Canvas 渲染（TS 沙箱无法绘图）

### 3. 群聊统计仪表盘（TS 示例）
- 每成员消息数统计
- 24 小时热力图
- TOP5 排行榜（渲染为图片发送）
- JSON 持久化存储

### 4. 自定义桌面壁纸
- 为 QQ 所有界面注入自定义壁纸背景
- Hook `Activity.onPostResume`，在 `android.R.id.content` index 0 插入 ImageView 壁纸层
- 清除 Window/content/Fragment 根的不透明背景让壁纸透出
- 分离式盒模糊（滑动窗口 O(w*h)）
- 暗化遮罩、透明度、3 种缩放方式（裁剪填充/适应/拉伸）
- 实时预览配置页

### 5. 防检测（防踢号）
- `PackageManager.getInstalledPackages` / `getInstalledApplications` 列表过滤（隐藏模块自身 + Xposed/LSPosed/Magisk 等 15+ 关键词包名）
- `Debug.isDebuggerConnected` → false
- 全进程加载（`process="All"`），默认开启
- **安全策略**：仅低风险 hook，不拦截 File/Runtime/SystemProperties，不影响 QQ 稳定性

## 📦 模块内置功能（继承自 QFun）
- [x] 群打卡
- [x] 防撤回 (带提示)
- [x] 自动续火
- [x] 消息复读 (+1)
- [x] 闪照破解
- [x] 屏蔽艾特全体
- [x] 简洁群管菜单
- [x] 一键点赞
- [x] 上传 APK 重命名
- [x] 去除回复自动艾特
- [x] 平板模式
- [x] 显示精确消息时间
- [x] 自定义骰子/猜拳/投篮
- [x] 移除表情回应
- [x] 解除风险网页拦截

# 技术栈

### 💻 核心语言与架构
- **Kotlin**：项目逻辑与 UI 代码，Coroutines 处理异步
- **MVVM**：Model-View-ViewModel 架构

### 🎨 界面与交互
- **Jetpack Compose (Material3)**：全声明式 UI，QAFUN 紫青渐变主题

### 🛠 逆向与 Hook
- **DexKit**：C++ 运行时字节码分析，抗混淆
- **Xposed API**：LibXposed 标准接口 + 兼容 Legacy Xposed

### 📦 数据与构建
- **Kotlin Serialization**：JSON 序列化
- **KSP**：编译时注解扫描，自动生成 Hook 注册表

### 🔌 动态扩展
- **QuickJS-kt + TypeScript**：替换 BeanShell，支持 TS 插件开发
- **TsTranspiler**：自研轻量 TS→JS 转译器

# 适配与运行环境

### Android 系统
- **最低版本**: Android 8.0 (API Level 26)
- **推荐版本**: Android 11.0+
- **架构支持**: `arm64-v8a`, `armeabi-v7a`

### 宿主应用
| 应用 | 推荐版本 | 备注 |
| :--- | :--- | :--- |
| **QQ** | `v9.1.25` 及以上 | 基于 NT 架构 |
| **TIM** | `v4.0.95` 及以上 | 部分兼容 |

### 框架支持

| 环境类型 | 推荐方案 | 说明 |
| :--- | :--- | :--- |
| **✅ Root 环境** | **LSPosed (Zygisk/Riru)** | **强烈推荐** |
| **🛡️ 免 Root 环境** | **LSPatch** | 修补 APK 集成 Xposed |

# TypeScript 插件开发

插件位于 QQ 私有目录 `QFun/plugins/[插件名]/main.ts`，示例：

```typescript
// main.ts
interface MsgData {
    msg: string;
    senderUin: number;
    // ...
}

function onLoad() {
    log("插件已加载");
    addItem("测试按钮", "onTestClick");
    addMenuItem("群菜单项", "onMenuClick", []);
}

function onTestClick() {
    toast("Hello from TS plugin!");
}

function onMsg(data: MsgData) {
    log("收到消息: " + data.msg);
}

function unLoadPlugin() {
    log("插件已卸载");
}
```

支持完整 TS 类型注解，由 TsTranspiler 自动转译为 JS 在 QuickJS 中运行。

# 反馈与日志

反馈时请注明：宿主版本、模块版本、运行框架及版本。

日志位置：`Android/data/[宿主包名]/QFun/[当前QQ号]/log/`

# 免责声明

1. **仅供学习交流**：本项目仅为 Android 开发与逆向工程技术的学习、交流与研究。
2. **风险自担**：使用本模块可能违反 QQ/TIM 用户协议，存在账号风险。**开发者不对任何后果负责。**
3. **非商业用途**：完全免费开源，禁止商业或非法用途。

**下载、安装或使用本模块即代表同意上述免责声明。**

<br/>

<div align="center">

### 致谢

本项目基于 [QFun](https://github.com/oneQAQone/QFun) 深度重构，感谢原项目及以下开源项目：

| Open Source Project | Role |
| :--- | :--- |
| [QFun](https://github.com/oneQAQone/QFun) | **原项目基础**<br>提供了完整的模块架构、Hook 系统与大量内置功能 |
| [QuickJS-kt](https://github.com/dokar3/quickjs-kt) | **TypeScript/JS 引擎**<br>替换 BeanShell，提供现代化的 TS 插件运行时 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | **Xposed 框架** |
| [DexKit](https://github.com/LuckyPray/DexKit) | **动态分析库** |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | **UI 工具包** |
| [LibXposed](https://github.com/libxposed/api) | **Hook 标准 API** |

</div>

<br/>

<div align="center">

*QAFUN · TypeScript Plugin Engine for QQ*

</div>

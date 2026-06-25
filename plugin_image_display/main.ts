// ==========================================
// 图片外显插件 - QAFUN TypeScript 脚本
// 将自定义文字渲染为精美图片并发送
// ==========================================

// ==================== 类型定义 ====================

/** 消息数据 (由宿主注入) */
interface MsgData {
    type: number;
    msgType: number;
    peerUin: string;
    peerUid: string;
    userUin: string;
    userUid: string;
    time: number;
    msgId: number;
    msg: string;
    path: string;
    atList: string[];
    atMap: Record<string, string>;
    contact: { chatType: number; peerUid: string; guildId: string };
}

/** 配置项 */
interface DisplayConfig {
    text: string;
    fontSize: number;
    color1: number;
    color2: number;
    padding: number;
    trigger: string;
    autoMode: boolean;
}

// ==================== 配置 ====================

const CFG = "image_display_config";

// 渐变预设
const GRADIENT_PRESETS: Array<{ name: string; c1: number; c2: number }> = [
    { name: "紫梦", c1: 0xff667eea, c2: 0xff764ba2 },
    { name: "海洋", c1: 0xff2193b0, c2: 0xff6dd5ed },
    { name: "日落", c1: 0xffff6e7f, c2: 0xffbfe9ff },
    { name: "森林", c1: 0xff11998e, c2: 0xff38ef7d },
    { name: "暗夜", c1: 0xff232526, c2: 0xff414345 },
    { name: "烈焰", c1: 0xfff12711, c2: 0xfff5af19 },
];

let presetIdx = getInt(CFG, "presetIdx", 0);

function loadConfig(): DisplayConfig {
    return {
        text: getString(CFG, "text", "图片外显"),
        fontSize: getInt(CFG, "fontSize", 64),
        color1: getInt(CFG, "color1", GRADIENT_PRESETS[presetIdx].c1),
        color2: getInt(CFG, "color2", GRADIENT_PRESETS[presetIdx].c2),
        padding: getInt(CFG, "padding", 50),
        trigger: getString(CFG, "trigger", "#外显"),
        autoMode: getBoolean(CFG, "autoMode", false),
    };
}

function saveConfig(cfg: DisplayConfig): void {
    putString(CFG, "text", cfg.text);
    putInt(CFG, "fontSize", cfg.fontSize);
    putInt(CFG, "color1", cfg.color1);
    putInt(CFG, "color2", cfg.color2);
    putInt(CFG, "padding", cfg.padding);
    putString(CFG, "trigger", cfg.trigger);
    putBoolean(CFG, "autoMode", cfg.autoMode);
    putInt(CFG, "presetIdx", presetIdx);
}

// ==================== 全局状态 ====================

let currentChatType = 0;
let currentPeerUin = "";
let isSendingImage = false;

// ==================== 核心逻辑 ====================

/** 渲染文字为图片并发送 */
function sendImageText(text: string, chatType: number, peerUin: string): void {
    try {
        if (!text) text = "图片外显";
        if (!peerUin) {
            qqToast(1, "未获取到聊天对象");
            return;
        }
        const cfg = loadConfig();
        const imgPath = renderTextImage(text, cfg.fontSize, cfg.color1, cfg.color2, cfg.padding);
        if (!imgPath) {
            qqToast(1, "图片渲染失败");
            return;
        }
        sendPic(peerUin, imgPath, chatType);
        qqToast(2, "外显图片已发送");
    } catch (e) {
        qqToast(1, "发送失败: " + e);
        log("error_log.txt", "sendImageText: " + e);
    }
}

/** 切换到下一个渐变预设 */
function nextPreset(): void {
    presetIdx = (presetIdx + 1) % GRADIENT_PRESETS.length;
    const p = GRADIENT_PRESETS[presetIdx];
    const cfg = loadConfig();
    cfg.color1 = p.c1;
    cfg.color2 = p.c2;
    saveConfig(cfg);
    qqToast(2, "渐变预设: " + p.name);
}

// ==================== 回调函数 ====================

/** 进入聊天界面 - 追踪当前会话 */
function chatInterface(chatType: number, peerUin: string, peerName: string): void {
    currentChatType = chatType;
    currentPeerUin = peerUin;
}

/** 收到消息 - 备用追踪当前会话 */
function onMsg(msg: MsgData): void {
    currentChatType = msg.type;
    currentPeerUin = msg.peerUin;
}

/** 发送消息预处理 - 触发词/自动模式转图片 */
function getMsg(content: string): string {
    if (isSendingImage) return content;
    if (!content) return content;

    const cfg = loadConfig();
    let shouldConvert = false;
    let textToConvert = "";

    if (cfg.autoMode) {
        shouldConvert = true;
        textToConvert = content;
    } else if (content.startsWith(cfg.trigger)) {
        shouldConvert = true;
        textToConvert = content.substring(cfg.trigger.length).trim();
        if (!textToConvert) textToConvert = cfg.text;
    }

    if (!shouldConvert) return content;
    if (!currentPeerUin) {
        qqToast(1, "未获取到聊天对象，请先打开悬浮球菜单");
        return content;
    }

    const text = textToConvert;
    isSendingImage = true;
    // 异步发送 (宿主 sendPic 内部不阻塞 JS 线程，此处用 setTimeout 模拟延迟)
    setTimeout(() => {
        try {
            const imgPath = renderTextImage(
                text, cfg.fontSize, cfg.color1, cfg.color2, cfg.padding
            );
            if (imgPath) sendPic(currentPeerUin, imgPath, currentChatType);
        } catch (e) {
            qqToast(1, "外显发送失败");
            log("error_log.txt", "getMsg sendImage: " + e);
        } finally {
            isSendingImage = false;
        }
    }, 300);

    // 返回空串抑制原始文字消息
    return "";
}

/** 悬浮球菜单: 发送外显图片 */
function onSendImage(chatType: number, peerUin: string, peerName: string): void {
    sendImageText(loadConfig().text, chatType, peerUin);
}

/** 悬浮球菜单: 切换渐变预设 */
function onSwitchPreset(chatType: number, peerUin: string, peerName: string): void {
    nextPreset();
}

/** 消息长按菜单: 将所选消息转为外显图片 */
function onConvertMsg(msg: MsgData): void {
    try {
        let text = msg.msg || "";
        text = text.replace(/\[pic=[^\]]*\]/g, "").trim();
        if (!text) {
            qqToast(1, "该消息无文本内容");
            return;
        }
        sendImageText(text, msg.type, msg.peerUin);
    } catch (e) {
        qqToast(1, "转换失败: " + e);
        log("error_log.txt", "onConvertMsg: " + e);
    }
}

/** 插件卸载 */
function unLoadPlugin(): void {
    log("log.txt", "图片外显插件已卸载");
}

// ==================== 初始化 ====================

// 注: setTimeout / console 由宿主 QuickJS 桥接注入，无需 polyfill

addItem("发送外显图片", "onSendImage");
addItem("切换渐变预设", "onSwitchPreset");
addMenuItem("转为外显图片", "onConvertMsg", []);

const _cfg = loadConfig();
log("log.txt", "===== 图片外显 TS 插件已加载 =====");
log("log.txt", "触发词: " + _cfg.trigger + " | 自动模式: " + _cfg.autoMode);
log("log.txt", "渐变: " + GRADIENT_PRESETS[presetIdx].name + " | 字号: " + _cfg.fontSize);

// ==========================================
// 群聊统计仪表盘插件 - QAFUN TypeScript 脚本
// 追踪群聊活跃度，生成统计卡片并发送
// ==========================================

// ==================== 类型定义 ====================

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

interface GroupStats {
    name: string;
    total: number;
    startTime: number;
    members: Record<string, number>;
    hours: number[]; // 长度 24
}

interface StatsStore {
    groups: Record<string, GroupStats>;
}

// ==================== 配置 ====================

const CFG = "chat_stats_data";

function loadStats(): StatsStore {
    const json = getString(CFG, "data", '{"groups":{}}');
    try {
        const obj = JSON.parse(json) as StatsStore;
        if (!obj.groups) obj.groups = {};
        return obj;
    } catch {
        return { groups: {} };
    }
}

function saveStats(store: StatsStore): void {
    putString(CFG, "data", JSON.stringify(store));
}

// ==================== 状态 ====================

let updateCounter = 0;

// ==================== 工具函数 ====================

function fmtNum(n: number): string {
    if (n >= 10000) return (n / 10000).toFixed(1) + "w";
    if (n >= 1000) return (n / 1000).toFixed(1) + "k";
    return n.toString();
}

function hoursToHms(secs: number): string {
    const h = Math.floor(secs / 3600) % 24;
    return String(h).padStart(2, "0") + ":00";
}

// ==================== 消息追踪 ====================

/** 收到消息 - 仅统计群消息 (type==2) */
function onMsg(msg: MsgData): void {
    if (msg.type !== 2) return;
    const peerUin = msg.peerUin;
    const userUin = msg.userUin;
    if (!peerUin || !userUin) return;

    const store = loadStats();
    const group: GroupStats = store.groups[peerUin] || {
        name: "",
        total: 0,
        startTime: 0,
        members: {},
        hours: new Array(24).fill(0),
    };

    const hour = Math.floor(msg.time / 3600) % 24;
    group.hours[hour] = (group.hours[hour] || 0) + 1;
    group.members[userUin] = (group.members[userUin] || 0) + 1;
    group.total += 1;
    if (group.startTime === 0) group.startTime = msg.time;
    store.groups[peerUin] = group;

    // 每 10 条保存一次
    updateCounter++;
    if (updateCounter % 10 === 0) {
        saveStats(store);
    }
}

// ==================== 统计卡片渲染 ====================

/** 生成统计文本报告并渲染为图片发送 */
function sendDashboard(chatType: number, peerUin: string): void {
    try {
        const store = loadStats();
        const group = store.groups[peerUin];
        if (!group || group.total === 0) {
            qqToast(1, "本群暂无统计数据");
            return;
        }

        // 排行榜 TOP5
        const sorted = Object.entries(group.members)
            .sort((a, b) => b[1] - a[1])
            .slice(0, 5);

        // 找最活跃时段
        let peakHour = 0;
        let peakCnt = 0;
        for (let i = 0; i < 24; i++) {
            if (group.hours[i] > peakCnt) {
                peakCnt = group.hours[i];
                peakHour = i;
            }
        }

        // 构建文本报告
        const lines: string[] = [];
        lines.push("QAFUN 群聊统计");
        lines.push("群: " + (group.name || peerUin));
        lines.push("总消息: " + fmtNum(group.total) + " | 成员: " + fmtNum(Object.keys(group.members).length));
        lines.push("最活跃: " + String(peakHour).padStart(2, "0") + ":00-" + String((peakHour + 1) % 24).padStart(2, "0") + ":00");
        lines.push("");
        lines.push("排行榜 TOP5");
        sorted.forEach((entry, idx) => {
            const medal = idx === 0 ? "1" : idx === 1 ? "2" : idx === 2 ? "3" : String(idx + 1);
            lines.push(medal + ". QQ" + entry[0] + "  " + entry[1] + "条");
        });
        lines.push("");
        lines.push("Powered by QAFUN");

        const report = lines.join("\n");
        const imgPath = renderTextImage(report, 36, 0xff7c5cff, 0xff00d9c0, 40);
        if (!imgPath) {
            qqToast(1, "渲染失败");
            return;
        }
        sendPic(peerUin, imgPath, chatType);
        qqToast(2, "统计卡片已发送");
    } catch (e) {
        qqToast(1, "发送失败: " + e);
        log("error_log.txt", "sendDashboard: " + e);
    }
}

/** 清空本群统计 */
function clearGroup(chatType: number, peerUin: string): void {
    try {
        const store = loadStats();
        delete store.groups[peerUin];
        saveStats(store);
        qqToast(2, "已清空本群统计");
    } catch (e) {
        qqToast(1, "清空失败: " + e);
    }
}

// ==================== 回调函数 ====================

/** 进入聊天界面 - 记录群名 */
function chatInterface(chatType: number, peerUin: string, peerName: string): void {
    if (chatType !== 2) return;
    const store = loadStats();
    if (store.groups[peerUin]) {
        store.groups[peerUin].name = peerName;
        saveStats(store);
    }
}

/** 消息长按菜单: 查看本群统计 */
function onShowStats(msg: MsgData): void {
    sendDashboard(msg.type, msg.peerUin);
}

/** 消息长按菜单: 清空本群统计 */
function onClearStats(msg: MsgData): void {
    clearGroup(msg.type, msg.peerUin);
}

/** 悬浮球菜单: 查看本群统计 */
function onDashboard(chatType: number, peerUin: string, peerName: string): void {
    sendDashboard(chatType, peerUin);
}

/** 插件卸载 */
function unLoadPlugin(): void {
    // 保存最新数据
    log("log.txt", "群聊统计插件已卸载");
}

// ==================== 初始化 ====================

addItem("查看本群统计", "onDashboard");
addMenuItem("查看本群统计", "onShowStats", []);
addMenuItem("清空本群统计", "onClearStats", []);

log("log.txt", "===== 群聊统计 TS 插件已加载 =====");

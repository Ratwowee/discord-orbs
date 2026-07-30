window.$;
let wpRequire = webpackChunkdiscord_app.push([[Symbol()], {}, r => r]);
webpackChunkdiscord_app.pop();

const allModules = Object.values(wpRequire.c);

function findExport(key) {
    const mod = allModules.find(x => x?.exports?.[key]);
    return mod?.exports[key];
}

function findProtoExport(key) {
    const mod = allModules.find(x => x?.exports?.[key]?.__proto__);
    return mod?.exports[key];
}

const supportedTasks = [
    "WATCH_VIDEO",
    "PLAY_ON_DESKTOP",
    "STREAM_ON_DESKTOP",
    "PLAY_ACTIVITY",
    "WATCH_VIDEO_ON_MOBILE"
];

let isApp = typeof DiscordNative !== "undefined";
let questQueue = [];

function getQuests() {
    const qStore = findProtoExport("A");
    if (!qStore?.quests) { console.log("Cannot find QuestsStore"); return []; }
    return [...qStore.quests.values()].filter(x => {
        if (!x.userStatus?.enrolledAt) return false;
        if (x.userStatus?.completedAt) return false;
        if (new Date(x.config.expiresAt).getTime() <= Date.now()) return false;
        const tc = x.config.taskConfig ?? x.config.taskConfigV2;
        return supportedTasks.some(y => Object.keys(tc.tasks).includes(y));
    });
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function apiPost(url, body) {
    try {
        const m = findExport("Bo");
        return await m.post({ url, body });
    } catch (e) { return null; }
}

async function apiGet(url) {
    try {
        const m = findExport("Bo");
        return await m.get({ url });
    } catch (e) { return null; }
}

function processNext() {
    const quest = questQueue.pop();
    if (!quest) { console.log("[AutoQuest] Toutes les quests sont finies!"); return; }

    const tc = quest.config.taskConfig ?? quest.config.taskConfigV2;
    const taskName = supportedTasks.find(x => tc.tasks[x] != null);
    const taskData = tc.tasks[taskName];
    const qName = quest.config.messages.questName;
    const secondsNeeded = taskData.target;
    const secondsDone = quest.userStatus?.progress?.[taskName]?.value ?? 0;

    console.log(`[AutoQuest] Processing: ${qName} [${taskName}] (${secondsDone}/${secondsNeeded}s)`);

    if (taskName === "WATCH_VIDEO" || taskName === "WATCH_VIDEO_ON_MOBILE") {
        doVideo(quest, taskName, secondsNeeded, secondsDone, qName);
    } else if (taskName === "PLAY_ON_DESKTOP") {
        doPlayDesktop(quest, taskData, secondsNeeded, secondsDone, qName);
    } else if (taskName === "STREAM_ON_DESKTOP") {
        doStreamDesktop(quest, taskData, secondsNeeded, secondsDone, qName);
    } else if (taskName === "PLAY_ACTIVITY") {
        doPlayActivity(quest, taskData, secondsNeeded, qName);
    }
}

function doVideo(quest, taskName, secondsNeeded, secondsDone, qName) {
    const speed = 7;
    (async () => {
        while (true) {
            const remaining = Math.min(speed, secondsNeeded - secondsDone);
            await sleep(remaining * 1000);

            const timestamp = secondsDone + speed;
            const res = await apiPost(`/quests/${quest.id}/video-progress`, {
                timestamp: Math.min(secondsNeeded, timestamp + Math.random())
            });

            secondsDone = Math.min(secondsNeeded, timestamp);
            const pct = Math.floor((secondsDone / secondsNeeded) * 100);
            console.log(`[AutoQuest] Video: ${secondsDone}/${secondsNeeded}s (${pct}%)`);

            if (timestamp >= secondsNeeded) break;
        }

        await apiPost(`/quests/${quest.id}/video-progress`, { timestamp: secondsNeeded });
        console.log(`[AutoQuest] Video completed: ${qName}`);
        processNext();
    })();
}

function doPlayDesktop(quest, taskData, secondsNeeded, secondsDone, qName) {
    if (!isApp) {
        console.log("[AutoQuest] PLAY_ON_DESKTOP marche pas sur le browser, utilise l'app desktop!");
        processNext();
        return;
    }

    const applicationId = quest.config.application?.id ?? taskData.applications?.[0]?.id;
    const pid = Math.floor(Math.random() * 30000) + 1000;

    apiGet(`/applications/public?application_ids=${applicationId}`).then(res => {
        if (!res) { console.log("[AutoQuest] Failed to get app data"); processNext(); return; }

        const appData = res.body[0];
        const exeName = appData.executables?.find(x => x.os === "win32")?.name?.replace(">", "") ?? appData.name.replace(/[\/\\:*?"<>|]/g, "");

        const fakeGame = {
            cmdLine: `C:\\Program Files\\${appData.name}\\${exeName}`,
            exeName,
            exePath: `c:/program files/${appData.name.toLowerCase()}/${exeName}`,
            hidden: false,
            isLauncher: false,
            id: applicationId,
            name: appData.name,
            pid,
            pidPath: [pid],
            processName: appData.name,
            start: Date.now()
        };

        const rgStore = findExport("Ay");
        if (!rgStore) { console.log("[AutoQuest] Cannot find RunningGameStore"); processNext(); return; }

        const realGames = rgStore.getRunningGames();
        const realGetRunningGames = rgStore.getRunningGames.bind(rgStore);
        const realGetGameForPID = rgStore.getGameForPID.bind(rgStore);

        rgStore.getRunningGames = () => [fakeGame];
        rgStore.getGameForPID = (p) => fakeGame.pid === p ? fakeGame : null;

        const fd = findExport("h");
        fd.dispatch({ type: "RUNNING_GAMES_CHANGE", removed: realGames, added: [fakeGame], games: [fakeGame] });

        console.log(`[AutoQuest] Spoofed: ${appData.name}. Attends ~${Math.ceil(secondsNeeded / 60)} min.`);

        const fn = (data) => {
            const progress = quest.config.configVersion === 1
                ? data.userStatus.streamProgressSeconds
                : Math.floor(data.userStatus.progress?.PLAY_ON_DESKTOP?.value ?? 0);

            console.log(`[AutoQuest] Game: ${progress}/${secondsNeeded}s`);

            if (progress >= secondsNeeded) {
                console.log(`[AutoQuest] Game completed: ${qName}`);
                rgStore.getRunningGames = realGetRunningGames;
                rgStore.getGameForPID = realGetGameForPID;
                fd.dispatch({ type: "RUNNING_GAMES_CHANGE", removed: [fakeGame], added: [], games: [] });
                fd.unsubscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
                processNext();
            }
        };

        fd.subscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
    });
}

function doStreamDesktop(quest, taskData, secondsNeeded, secondsDone, qName) {
    if (!isApp) {
        console.log("[AutoQuest] STREAM_ON_DESKTOP marche pas sur le browser, utilise l'app desktop!");
        processNext();
        return;
    }

    const applicationId = quest.config.application?.id ?? taskData.applications?.[0]?.id;
    const pid = Math.floor(Math.random() * 30000) + 1000;

    const streamStore = allModules.find(x => x?.exports?.A?.__proto__?.getStreamerActiveStreamMetadata)?.exports.A;
    if (!streamStore) { console.log("[AutoQuest] Cannot find StreamingStore"); processNext(); return; }

    const realFunc = streamStore.getStreamerActiveStreamMetadata.bind(streamStore);
    streamStore.getStreamerActiveStreamMetadata = () => ({ id: applicationId, pid, sourceName: null });

    const fd = findExport("h");
    console.log(`[AutoQuest] Spoofed stream. Stream dans un VC pour ~${Math.ceil(secondsNeeded / 60)} min.`);

    const fn = (data) => {
        const progress = quest.config.configVersion === 1
            ? data.userStatus.streamProgressSeconds
            : Math.floor(data.userStatus.progress?.STREAM_ON_DESKTOP?.value ?? 0);

        console.log(`[AutoQuest] Stream: ${progress}/${secondsNeeded}s`);

        if (progress >= secondsNeeded) {
            console.log(`[AutoQuest] Stream completed: ${qName}`);
            streamStore.getStreamerActiveStreamMetadata = realFunc;
            fd.unsubscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
            processNext();
        }
    };

    fd.subscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
}

function doPlayActivity(quest, taskData, secondsNeeded, qName) {
    const chStore = findProtoExport("A");
    const guildStore = findExport("Ay");

    let channelId = null;

    try {
        channelId = chStore.getSortedPrivateChannels?.()[0]?.id;
    } catch (e) {}

    if (!channelId && guildStore) {
        try {
            const guilds = Object.values(guildStore.getAllGuilds()).filter(x => x != null && x.VOCAL?.length > 0);
            channelId = guilds[0]?.VOCAL[0]?.channel?.id;
        } catch (e) {}
    }

    if (!channelId) {
        console.log("[AutoQuest] Pas de voice channel trouvé pour PLAY_ACTIVITY!");
        processNext();
        return;
    }

    const streamKey = `call:${channelId}:1`;
    console.log(`[AutoQuest] Activity: ${qName}. Attends ~${Math.ceil(secondsNeeded / 60)} min.`);

    (async () => {
        while (true) {
            const res = await apiPost(`/quests/${quest.id}/heartbeat`, { stream_key: streamKey, terminal: false });
            if (!res) { await sleep(5000); continue; }

            const progress = res.body?.progress?.PLAY_ACTIVITY?.value ?? 0;
            console.log(`[AutoQuest] Activity: ${progress}/${secondsNeeded}s`);

            if (progress >= secondsNeeded) {
                await apiPost(`/quests/${quest.id}/heartbeat`, { stream_key: streamKey, terminal: true });
                break;
            }
            await sleep(20000);
        }

        console.log(`[AutoQuest] Activity completed: ${qName}`);
        processNext();
    })();
}

const allQuests = getQuests();
questQueue = [...allQuests];

if (allQuests.length === 0) {
    console.log("[AutoQuest] Aucune quest trouvée!");
} else {
    console.log(`[AutoQuest] ${allQuests.length} quest(s) trouvée(s):`);
    allQuests.forEach((q, i) => {
        const tc = q.config.taskConfig ?? q.config.taskConfigV2;
        const tn = supportedTasks.find(x => tc.tasks[x] != null);
        const td = tc.tasks[tn];
        const p = q.userStatus?.progress?.[tn]?.value ?? 0;
        console.log(`  ${i + 1}. ${q.config.messages.questName} [${tn}] ${p}/${td.target}s`);
    });
    processNext();
}
// The GameStore and StreamStore variables were using the same find(“Ay”) and findProto(“A”)  
//so JS was crashing because they were declared twice in the same fucking scope bruv scope
// So I defined inline within each function instead of at the beginning wich should bet better right ?
// Also there was no fucking bind() on the stores so the context was broken and my dumbass didn't noticed that 😔

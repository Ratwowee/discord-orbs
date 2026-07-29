window.$;
let wpRequire = webpackChunkdiscord_app.push([[Symbol()], {}, r => r]);
webpackChunkdiscord_app.pop();

const Stores = Object.values(wpRequire.c);
const find = (key) => Stores.find(x => x?.exports?.[key])?.exports[key];
const findProto = (key) => Stores.find(x => x?.exports?.[key]?.__proto__)?.exports[key];

const ApplicationStreamingStore = findProto("A");
const RunningGameStore = find("Ay");
const QuestsStore = findProto("A");
const ChannelStore = findProto("A");
const GuildChannelStore = find("Ay");
const FluxDispatcher = find("h");
const api = find("Bo");

const supportedTasks = [
    "WATCH_VIDEO",
    "PLAY_ON_DESKTOP",
    "STREAM_ON_DESKTOP",
    "PLAY_ACTIVITY",
    "WATCH_VIDEO_ON_MOBILE"
];

const config = {
    videoSpeed: 7,
    heartbeatInterval: 20000,
    logPrefix: "[AutoQuest]"
};

function log(msg) {
    console.log(`${config.logPrefix} ${msg}`);
}

function warn(msg) {
    console.warn(`${config.logPrefix} ${msg}`);
}

function error(msg) {
    console.error(`${config.logPrefix} ${msg}`);
}

function getQuests() {
    const quests = [...QuestsStore.quests.values()].filter(x => {
        if (!x.userStatus?.enrolledAt) return false;
        if (x.userStatus?.completedAt) return false;
        if (new Date(x.config.expiresAt).getTime() <= Date.now()) return false;

        const taskConfig = x.config.taskConfig ?? x.config.taskConfigV2;
        const hasTask = supportedTasks.some(y => Object.keys(taskConfig.tasks).includes(y));
        return hasTask;
    });
    return quests;
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function fetchApi(method, url, body = null) {
    try {
        const opts = { url };
        if (body) opts.body = body;
        if (method === "post") return await api.post(opts);
        if (method === "get") return await api.get(opts);
    } catch (e) {
        warn(`API error (${url}): ${e.message}`);
        return null;
    }
}

async function spoofVideo(quest) {
    const taskConfig = quest.config.taskConfig ?? quest.config.taskConfigV2;
    const taskName = supportedTasks.find(x => taskConfig.tasks[x] != null);
    const taskData = taskConfig.tasks[taskName];
    const secondsNeeded = taskData.target;
    let secondsDone = quest.userStatus?.progress?.[taskName]?.value ?? 0;
    const questName = quest.config.messages.questName;

    log(`Video spoofing: ${questName} (${secondsDone}/${secondsNeeded}s)`);

    while (true) {
        const remaining = Math.min(config.videoSpeed, secondsNeeded - secondsDone);
        await sleep(remaining * 1000);

        const timestamp = secondsDone + config.videoSpeed;
        const res = await fetchApi("post", `/quests/${quest.id}/video-progress`, {
            timestamp: Math.min(secondsNeeded, timestamp + Math.random())
        });

        if (!res) {
            warn("Video progress request failed, retrying...");
            await sleep(5000);
            continue;
        }

        secondsDone = Math.min(secondsNeeded, timestamp);
        const progress = Math.floor((secondsDone / secondsNeeded) * 100);
        log(`Video progress: ${secondsDone}/${secondsNeeded}s (${progress}%)`);

        if (timestamp >= secondsNeeded) break;
    }

    const finalRes = await fetchApi("post", `/quests/${quest.id}/video-progress`, {
        timestamp: secondsNeeded
    });

    log(`Video quest completed: ${questName}`);
    return true;
}

function spoofGame(quest) {
    const isApp = typeof DiscordNative !== "undefined";
    if (!isApp) {
        warn("PLAY_ON_DESKTOP requires Discord desktop app!");
        return false;
    }

    const taskConfig = quest.config.taskConfig ?? quest.config.taskConfigV2;
    const taskName = "PLAY_ON_DESKTOP";
    const taskData = taskConfig.tasks[taskName];
    const applicationId = quest.config.application?.id ?? taskData.applications?.[0]?.id;
    const secondsNeeded = taskData.target;
    const questName = quest.config.messages.questName;
    const pid = Math.floor(Math.random() * 30000) + 1000;

    log(`Game spoofing: ${questName}`);

    return fetchApi("get", `/applications/public?application_ids=${applicationId}`).then(res => {
        if (!res) {
            warn("Failed to fetch application data");
            return false;
        }

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

        const realGames = RunningGameStore.getRunningGames();
        const realGetRunningGames = RunningGameStore.getRunningGames;
        const realGetGameForPID = RunningGameStore.getGameForPID;

        RunningGameStore.getRunningGames = () => [fakeGame];
        RunningGameStore.getGameForPID = (p) => fakeGame.pid === p ? fakeGame : null;

        FluxDispatcher.dispatch({
            type: "RUNNING_GAMES_CHANGE",
            removed: realGames,
            added: [fakeGame],
            games: [fakeGame]
        });

        log(`Spoofed as: ${appData.name}. Waiting ~${Math.ceil((secondsNeeded) / 60)} min...`);

        const fn = (data) => {
            const progress = quest.config.configVersion === 1
                ? data.userStatus.streamProgressSeconds
                : Math.floor(data.userStatus.progress.PLAY_ON_DESKTOP?.value ?? 0);

            log(`Game progress: ${progress}/${secondsNeeded}s`);

            if (progress >= secondsNeeded) {
                log(`Game quest completed: ${questName}`);
                RunningGameStore.getRunningGames = realGetRunningGames;
                RunningGameStore.getGameForPID = realGetGameForPID;
                FluxDispatcher.dispatch({
                    type: "RUNNING_GAMES_CHANGE",
                    removed: [fakeGame],
                    added: [],
                    games: []
                });
                FluxDispatcher.unsubscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
                processNext();
            }
        };

        FluxDispatcher.subscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
        return true;
    });
}

function spoofStream(quest) {
    const isApp = typeof DiscordNative !== "undefined";
    if (!isApp) {
        warn("STREAM_ON_DESKTOP requires Discord desktop app!");
        return false;
    }

    const taskConfig = quest.config.taskConfig ?? quest.config.taskConfigV2;
    const taskData = taskConfig.tasks["STREAM_ON_DESKTOP"];
    const applicationId = quest.config.application?.id ?? taskData.applications?.[0]?.id;
    const secondsNeeded = taskData.target;
    const questName = quest.config.messages.questName;
    const pid = Math.floor(Math.random() * 30000) + 1000;

    const realFunc = ApplicationStreamingStore.getStreamerActiveStreamMetadata;
    ApplicationStreamingStore.getStreamerActiveStreamMetadata = () => ({
        id: applicationId,
        pid,
        sourceName: null
    });

    log(`Stream spoofing: ${questName}. Stream in VC for ~${Math.ceil(secondsNeeded / 60)} min.`);

    const fn = (data) => {
        const progress = quest.config.configVersion === 1
            ? data.userStatus.streamProgressSeconds
            : Math.floor(data.userStatus.progress.STREAM_ON_DESKTOP?.value ?? 0);

        log(`Stream progress: ${progress}/${secondsNeeded}s`);

        if (progress >= secondsNeeded) {
            log(`Stream quest completed: ${questName}`);
            ApplicationStreamingStore.getStreamerActiveStreamMetadata = realFunc;
            FluxDispatcher.unsubscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
            processNext();
        }
    };

    FluxDispatcher.subscribe("QUESTS_SEND_HEARTBEAT_SUCCESS", fn);
    return true;
}

async function spoofActivity(quest) {
    const taskConfig = quest.config.taskConfig ?? quest.config.taskConfigV2;
    const taskData = taskConfig.tasks["PLAY_ACTIVITY"];
    const secondsNeeded = taskData.target;
    const questName = quest.config.messages.questName;

    let channelId = ChannelStore.getSortedPrivateChannels()[0]?.id;
    if (!channelId) {
        const guilds = Object.values(GuildChannelStore.getAllGuilds()).filter(x => x != null && x.VOCAL.length > 0);
        channelId = guilds[0]?.VOCAL[0]?.channel?.id;
    }

    if (!channelId) {
        warn("No voice channel found for PLAY_ACTIVITY!");
        return false;
    }

    const streamKey = `call:${channelId}:1`;
    log(`Activity spoofing: ${questName}. Waiting ~${Math.ceil(secondsNeeded / 60)} min.`);

    while (true) {
        const res = await fetchApi("post", `/quests/${quest.id}/heartbeat`, {
            stream_key: streamKey,
            terminal: false
        });

        if (!res) {
            warn("Heartbeat failed, retrying...");
            await sleep(5000);
            continue;
        }

        const progress = res.body.progress?.PLAY_ACTIVITY?.value ?? 0;
        log(`Activity progress: ${progress}/${secondsNeeded}s`);

        if (progress >= secondsNeeded) {
            await fetchApi("post", `/quests/${quest.id}/heartbeat`, {
                stream_key: streamKey,
                terminal: true
            });
            break;
        }

        await sleep(config.heartbeatInterval);
    }

    log(`Activity quest completed: ${questName}`);
    return true;
}

function processNext() {
    const quest = questQueue.pop();
    if (!quest) {
        log("All quests processed!");
        return;
    }

    const taskConfig = quest.config.taskConfig ?? quest.config.taskConfigV2;
    const taskName = supportedTasks.find(x => taskConfig.tasks[x] != null);

    log(`Processing: ${quest.config.messages.questName} [${taskName}]`);

    if (taskName === "WATCH_VIDEO" || taskName === "WATCH_VIDEO_ON_MOBILE") {
        spoofVideo(quest).then(() => processNext());
    } else if (taskName === "PLAY_ON_DESKTOP") {
        spoofGame(quest);
    } else if (taskName === "STREAM_ON_DESKTOP") {
        spoofStream(quest);
    } else if (taskName === "PLAY_ACTIVITY") {
        spoofActivity(quest).then(() => processNext());
    }
}

const quests = getQuests();
let questQueue = [...quests];

if (quests.length === 0) {
    log("No uncompleted quests found.");
} else {
    log(`Found ${quests.length} quest(s):`);
    quests.forEach((q, i) => {
        const taskConfig = q.config.taskConfig ?? q.config.taskConfigV2;
        const taskName = supportedTasks.find(x => taskConfig.tasks[x] != null);
        const taskData = taskConfig.tasks[taskName];
        const progress = q.userStatus?.progress?.[taskName]?.value ?? 0;
        log(`  ${i + 1}. ${q.config.messages.questName} [${taskName}] ${progress}/${taskData.target}s`);
    });
    processNext();
}

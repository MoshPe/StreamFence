(function () {
    const NS = "http://www.w3.org/2000/svg";
    const TICK_MS = 1000;
    const SSE_RETRY_MS = 1000;
    const MAX_EVENTS = 50;
    const HIGH_FREQUENCY_CATEGORIES = new Set(["publish", "topic_message"]);
    const STREAM_EVENTS = [
        "message",
        "server_starting",
        "server_started",
        "server_stopping",
        "server_stopped",
        "client_connected",
        "client_disconnected",
        "subscribed",
        "unsubscribed",
        "publish",
        "publish_accepted",
        "publish_rejected",
        "queue_overflow",
        "auth_rejected",
        "retry",
        "retry_exhausted",
        "topic_message",
        "connect",
        "disconnect",
        "error",
        "ack"
    ];
    const $ = (id) => document.getElementById(id);
    const el = {
        connection: $("connection-status"), runtime: $("runtime-status"), preset: $("preset-status"),
        presetDesc: $("preset-description"), presetGrid: $("preset-grid"),
        timeline: $("timeline"), timelineMeta: $("timeline-meta"),
        namespaceGrid: $("namespace-grid"), namespaceMeta: $("namespace-meta"),
        story: $("config-story"), storyMeta: $("config-story-meta"),
        deepDive: $("deep-dive"), deepDiveMeta: $("deep-dive-meta"),
        payloadMeta: $("payload-meta"), payloadDetails: $("payload-details"), payloadPreview: $("payload-preview"),
        raw: $("raw-counters"), rawMeta: $("raw-meta"), processes: $("process-list"),
        throughputChart: $("chart-throughput"), networkChart: $("chart-network"), clientsChart: $("chart-clients"),
        throughputMeta: $("chart-throughput-meta"), networkMeta: $("chart-network-meta"), clientsMeta: $("chart-clients-meta"),
        manualStatus: $("manual-status"), namespace: $("manual-namespace"), topic: $("manual-topic"), token: $("manual-token"),
        payload: $("manual-payload"), targetClient: $("target-client-id"), persona: $("persona-select"),
        clients: $("kpi-clients"), totals: $("kpi-total-messages"), throughput: $("kpi-throughput"),
        sendRate: $("kpi-send-rate"), sendTotal: $("kpi-send-total"), recvRate: $("kpi-receive-rate"),
        recvTotal: $("kpi-receive-total"), pressure: $("kpi-pressure"), pressureMeta: $("kpi-pressure-breakdown"),
        socketUrl: $("kpi-socket-url"), metricsUrl: $("kpi-metrics-url"), processCount: $("kpi-processes")
    };
    const state = {
        config: null, dashboard: null, payloadMeta: null, source: null, manualSocket: null,
        poller: null, sseRetry: null, timelineBuckets: new Map()
    };

    bind();
    boot();

    async function boot() {
        try {
            await refreshConfig();
            await refreshDashboard();
            openEvents();
            state.poller = window.setInterval(tick, TICK_MS);
        } catch (error) {
            status(el.runtime, "Dashboard unavailable", "red");
            timeline("Failed to bootstrap dashboard: " + error.message, "error", "red");
        }
    }

    function bind() {
        $("manual-connect").addEventListener("click", connectManual);
        $("manual-disconnect").addEventListener("click", () => disconnectManual(true));
        $("manual-subscribe").addEventListener("click", subscribeManual);
        $("manual-unsubscribe").addEventListener("click", unsubscribeManual);
        $("manual-publish").addEventListener("click", publishManual);
        $("manual-bulk").addEventListener("click", publishBulkManual);
        document.querySelectorAll("[data-console-action]").forEach((button) => {
            button.addEventListener("click", () => runAction(button.dataset.consoleAction));
        });
        el.namespace.addEventListener("change", renderTopics);
    }

    async function tick() {
        flushSampledTimeline();
        await refreshDashboard();
        if (state.config && state.dashboard && state.config.activePreset !== state.dashboard.presetId) {
            await refreshConfig();
        }
    }

    async function refreshConfig() {
        state.config = await json("/api/demo-config");
        el.socketUrl.textContent = state.config.socketUrl;
        el.metricsUrl.textContent = state.config.metricsUrl;
        el.presetDesc.textContent = state.config.activePresetDescription || "Preset metadata loaded";
        renderPresetButtons();
        renderStory();
        renderDeepDive();
        renderControls();
        renderPayloadMetadata();
        status(el.preset, "Preset " + state.config.activePreset, "blue");
    }

    async function refreshDashboard() {
        state.dashboard = await json("/api/dashboard");
        renderDashboard();
    }

    function renderPresetButtons() {
        el.presetGrid.innerHTML = "";
        (state.config.presets || []).forEach((preset) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "preset-button" + (preset.id === state.config.activePreset ? " is-active" : "");
            button.innerHTML = "<span class=\"preset-button__title\">" + esc(preset.label) + "</span>"
                + "<span class=\"preset-button__copy\">" + esc(preset.description) + "</span>"
                + "<span class=\"preset-button__badge\">" + esc(preset.id) + "</span>";
            button.addEventListener("click", async () => {
                if (preset.id === state.config.activePreset) return;
                try {
                    status(el.preset, "Switching to " + preset.id, "amber");
                    await json("/api/actions/apply-preset", { method: "POST", body: JSON.stringify({ presetId: preset.id }) });
                    timeline("Preset switch requested for " + preset.id, "apply-preset", "amber");
                    await refreshConfig();
                    await refreshDashboard();
                } catch (error) {
                    timeline("Preset switch failed: " + error.message, "error", "red");
                    status(el.preset, "Preset switch failed", "red");
                }
            });
            el.presetGrid.appendChild(button);
        });
    }

    function renderDashboard() {
        const d = state.dashboard;
        if (!d) return;
        const sent = sumNs("messagesPublished");
        const recv = sumNs("messagesReceived");
        const pressure = (d.retryCount || 0) + (d.queueOverflow || 0) + (d.dropped || 0) + (d.coalesced || 0);
        el.clients.textContent = num(d.activeClients);
        el.processCount.textContent = ((d.processes || []).length) + " processes";
        el.totals.textContent = num(d.totalMessages);
        el.throughput.textContent = rate(d.messagesPerSecond, "msg/s");
        el.sendRate.textContent = bytes(d.sendBytesPerSecond) + "/s";
        el.sendTotal.textContent = "messages " + num(sent);
        el.recvRate.textContent = bytes(d.receiveBytesPerSecond) + "/s";
        el.recvTotal.textContent = "messages " + num(recv);
        el.pressure.textContent = num(pressure);
        el.pressureMeta.textContent = "retries " + num(d.retryCount) + ", overflow " + num(d.queueOverflow)
            + ", dropped " + num(d.dropped) + ", coalesced " + num(d.coalesced);
        el.namespaceMeta.textContent = ((d.namespaces || []).length) + " active namespaces";
        el.rawMeta.textContent = "Last updated " + stamp(d.lastUpdated);
        el.throughputMeta.textContent = "Latest " + rate(d.messagesPerSecond, "msg/s");
        el.networkMeta.textContent = "Send " + bytes(d.sendBytesPerSecond) + "/s | Receive " + bytes(d.receiveBytesPerSecond) + "/s";
        el.clientsMeta.textContent = "Latest " + num(d.activeClients) + " sockets";
        status(el.runtime, d.lastError ? d.lastError : d.scenarioPaused ? "Scenario paused" : "Live metrics flowing", d.lastError ? "red" : d.scenarioPaused ? "amber" : "green");
        status(el.preset, "Preset " + d.presetId, "blue");
        renderNamespaces();
        renderRaw();
        renderProcesses();
        renderPayloadMetadata();
        renderChart(el.throughputChart, d.history, [{ key: "messagesPerSecond", color: "#f05d23" }]);
        renderChart(el.networkChart, d.history, [{ key: "sendBytesPerSecond", color: "#2c3d55" }, { key: "receiveBytesPerSecond", color: "#1e8b8f" }]);
        renderChart(el.clientsChart, d.history, [{ key: "activeClients", color: "#b26c00" }]);
    }

    function renderNamespaces() {
        el.namespaceGrid.innerHTML = "";
        const byConfig = new Map((state.config.namespaces || []).map((n) => [n.namespace, n]));
        const list = state.dashboard.namespaces || [];
        if (!list.length) return el.namespaceGrid.appendChild(empty("Waiting for namespace metrics"));
        list.forEach((ns) => {
            const cfg = byConfig.get(ns.namespace) || {};
            const card = document.createElement("article");
            card.className = "namespace-card";
            card.innerHTML = "<h3>" + esc(ns.namespace) + "</h3>"
                + "<p class=\"namespace-card__meta\">" + esc((cfg.deliveryMode || "BEST_EFFORT") + " / " + (cfg.overflowAction || "REJECT_NEW"))
                + " | polling " + String(Boolean(cfg.allowPolling)) + " | topics " + esc((cfg.topics || []).join(", ")) + "</p>"
                + "<div class=\"namespace-card__stats\">"
                + stat("Clients", num(ns.activeClients)) + stat("Published", num(ns.messagesPublished))
                + stat("Received", num(ns.messagesReceived)) + stat("Retries", num(ns.retryCount))
                + stat("Overflow", num(ns.queueOverflow)) + stat("Coalesced", num(ns.coalesced))
                + "</div>";
            el.namespaceGrid.appendChild(card);
        });
    }

    function renderStory() {
        el.story.innerHTML = "";
        const cfg = state.config;
        const head = document.createElement("article");
        head.className = "story-card";
        head.innerHTML = "<h3>" + esc(cfg.activePresetLabel || cfg.activePreset) + "</h3>"
            + "<p class=\"story-card__meta\">" + esc(cfg.activePresetDescription || "Preset description unavailable") + "</p>"
            + "<div class=\"story-card__stats\">"
            + stat("Transport", cfg.transportMode || "WS") + stat("Auth", cfg.authMode || "TOKEN")
            + stat("Socket", cfg.socketUrl) + stat("Metrics", cfg.metricsUrl)
            + "</div>";
        el.story.appendChild(head);
        (cfg.namespaces || []).forEach((ns) => {
            const card = document.createElement("article");
            card.className = "story-card";
            card.innerHTML = "<h3>" + esc(ns.namespace) + "</h3>"
                + "<p class=\"story-card__meta\">delivery " + esc(ns.deliveryMode || "BEST_EFFORT")
                + ", overflow " + esc(ns.overflowAction || "REJECT_NEW") + ", auth " + String(Boolean(ns.tokenRequired)) + "</p>"
                + "<div class=\"story-card__stats\">"
                + stat("Coalesce", String(Boolean(ns.coalesce))) + stat("Max In Flight", num(ns.maxInFlight || 1))
                + stat("Queue Msg", num(ns.maxQueuedMessagesPerClient || 0)) + stat("Queue Bytes", bytes(ns.maxQueuedBytesPerClient || 0))
                + stat("Ack Timeout", num(ns.ackTimeoutMs || 0) + " ms") + stat("Retries", num(ns.maxRetries || 0))
                + "</div>";
            el.story.appendChild(card);
        });
        el.storyMeta.textContent = "Token hint: " + (cfg.tokenHint || "n/a");
    }

    function renderDeepDive() {
        el.deepDive.innerHTML = "";
        const cfg = state.config;
        if (!cfg) {
            return;
        }
        const namespaces = cfg.namespaces || [];
        const personas = cfg.personas || [];
        const namespaceByPath = new Map(namespaces.map((ns) => [ns.namespace, ns]));
        const intro = document.createElement("article");
        intro.className = "deep-dive-card";
        intro.innerHTML = "<p class=\"deep-dive-card__eyebrow\">Preset Intent</p>"
            + "<h3>" + esc(cfg.activePresetLabel || cfg.activePreset) + "</h3>"
            + "<p class=\"deep-dive-card__copy\">" + esc(cfg.activePresetDescription || "Preset description unavailable.") + "</p>"
            + "<p class=\"deep-dive-card__copy\">This profile runs with global transport mode "
            + esc(cfg.transportMode || "default") + " and auth mode " + esc(cfg.authMode || "default")
            + ". The sections below describe exactly what the wsserver side enforces and what each demo client swarm is doing.</p>"
            + "<div class=\"deep-dive-card__facts\">"
            + stat("Namespaces", num(namespaces.length)) + stat("Personas", num(personas.length))
            + stat("Socket", cfg.socketUrl || "n/a") + stat("Metrics", cfg.metricsUrl || "n/a")
            + "</div>";
        el.deepDive.appendChild(intro);

        namespaces.forEach((ns) => el.deepDive.appendChild(buildServerDeepDive(ns, cfg)));
        personas.forEach((persona) => el.deepDive.appendChild(buildClientDeepDive(persona, namespaceByPath.get(persona.namespace), cfg)));
        el.deepDiveMeta.textContent = "Generated from active preset, namespace, and persona config";
    }

    function buildServerDeepDive(ns, cfg) {
        const card = document.createElement("article");
        card.className = "deep-dive-card";
        card.innerHTML = "<p class=\"deep-dive-card__eyebrow\">Server Namespace</p>"
            + "<h3>" + esc(ns.namespace) + "</h3>"
            + "<p class=\"deep-dive-card__copy\">" + esc(serverDeliveryNarrative(ns)) + "</p>"
            + "<p class=\"deep-dive-card__copy\">" + esc(serverAccessNarrative(ns, cfg)) + "</p>"
            + "<ul class=\"deep-dive-card__list\">"
            + li("Topics served: " + (ns.topics || []).join(", "))
            + li("Overflow action: " + overflowDescription(ns))
            + li("Queue limits: " + num(ns.maxQueuedMessagesPerClient || 0) + " messages and " + bytes(ns.maxQueuedBytesPerClient || 0) + " per client")
            + li("Coalescing: " + (ns.coalesce ? "enabled, so newer snapshots can replace stale queued ones" : "disabled, so each queued message remains distinct"))
            + li("Transport: " + transportDescription(ns, cfg))
            + "</ul>"
            + "<div class=\"deep-dive-card__facts\">"
            + stat("Delivery", ns.deliveryMode || "n/a")
            + stat("Ack Timeout", formatMs(ns.ackTimeoutMs))
            + stat("Retries", num(ns.maxRetries || 0))
            + stat("Max In Flight", num(ns.maxInFlight || 0))
            + "</div>";
        return card;
    }

    function buildClientDeepDive(persona, ns, cfg) {
        const card = document.createElement("article");
        card.className = "deep-dive-card";
        card.innerHTML = "<p class=\"deep-dive-card__eyebrow\">Client Swarm</p>"
            + "<h3>" + esc(persona.name) + "</h3>"
            + "<p class=\"deep-dive-card__copy\">" + esc(clientPersonaNarrative(persona, ns)) + "</p>"
            + "<p class=\"deep-dive-card__copy\">" + esc(clientAckNarrative(persona, ns)) + "</p>"
            + "<ul class=\"deep-dive-card__list\">"
            + li("Namespace: " + persona.namespace)
            + li("Topics: " + (persona.topics || []).join(", "))
            + li("Connections opened: " + num(persona.connectionCount || 0))
            + li("Publish cadence: every " + formatMs(persona.publishCadenceMs))
            + li("Payload type: " + persona.payloadKind)
            + li("Token usage: " + (persona.tokenRequired ? "the swarm includes the demo token `change-me` on connect and publish/subscribe requests" : "no token is needed for this swarm"))
            + "</ul>"
            + "<div class=\"deep-dive-card__facts\">"
            + stat("Ack Delay", formatMs(persona.ackDelayMs))
            + stat("Payload", persona.payloadKind || "n/a")
            + stat("Auth", persona.tokenRequired ? "token" : "anonymous")
            + stat("Namespace", persona.namespace || "n/a")
            + "</div>";
        return card;
    }

    function serverDeliveryNarrative(ns) {
        if ((ns.deliveryMode || "") === "AT_LEAST_ONCE") {
            return "This namespace uses AT_LEAST_ONCE delivery. The server stamps each outbound topic-message with a message id and ackRequired=true, keeps up to "
                + num(ns.maxInFlight || 0) + " reliable deliveries in flight per client, waits " + formatMs(ns.ackTimeoutMs)
                + " for an ack, and retries up to " + num(ns.maxRetries || 0) + " times before it surfaces retry exhaustion.";
        }
        return "This namespace uses best-effort style delivery. Messages are pushed immediately without per-message acknowledgement tracking, so the emphasis is raw throughput and freshness instead of retry bookkeeping.";
    }

    function serverAccessNarrative(ns, cfg) {
        const auth = ns.tokenRequired
            ? "Clients must authenticate with the demo token before they can subscribe or publish on this namespace."
            : "Clients may connect anonymously on this namespace.";
        const transport = ns.allowPolling
            ? "Polling fallback is allowed here, so websocket-capable and polling-capable clients can both participate."
            : "Polling fallback is disabled here, so clients need websocket-capable transport.";
        return auth + " " + transport + " The global server transport mode is " + (cfg.transportMode || "default") + ".";
    }

    function overflowDescription(ns) {
        switch (ns.overflowAction) {
            case "COALESCE":
                return "COALESCE, so queued snapshots for the same topic collapse to the newest state under pressure";
            case "DROP_OLDEST":
                return "DROP_OLDEST, so older queued messages are discarded to make room for fresher traffic";
            case "REJECT_NEW":
            default:
                return "REJECT_NEW, so once the queue is full the server rejects additional messages for that client/topic lane";
        }
    }

    function transportDescription(ns, cfg) {
        if (!ns.allowPolling) {
            return "websocket-oriented delivery only on this namespace";
        }
        return "namespace allows polling fallback within global mode " + (cfg.transportMode || "default");
    }

    function clientPersonaNarrative(persona, ns) {
        const cadence = "It opens " + num(persona.connectionCount || 0) + " concurrent client connections and publishes every " + formatMs(persona.publishCadenceMs) + ".";
        const payload = persona.payloadKind === "BINARY"
            ? "Payloads are large binary-style messages that carry declaredSizeBytes and contentType metadata."
            : "Payloads are JSON messages that demonstrate topic update flow.";
        return persona.name + " runs against " + persona.namespace + " and publishes to " + (persona.topics || []).join(", ") + ". " + cadence + " " + payload;
    }

    function clientAckNarrative(persona, ns) {
        if (!ns || ns.deliveryMode !== "AT_LEAST_ONCE") {
            return "Because this namespace does not require reliable acknowledgements, the client swarm does not need to ack each delivered message.";
        }
        if ((persona.ackDelayMs || 0) > 0) {
            return "When this client receives a reliable topic-message from the server, it must send ack { topic, messageId }. This swarm intentionally delays that ack by "
                + formatMs(persona.ackDelayMs) + " so you can see retries, queue pressure, and retry exhaustion behavior.";
        }
        return "When this client receives a reliable topic-message from the server, it must send ack { topic, messageId } back promptly. This swarm auto-acks immediately so the reliable lane stays healthy.";
    }

    function renderRaw() {
        const d = state.dashboard;
        const cards = [
            ["Messages / sec", rate(d.messagesPerSecond, "msg/s")], ["Send bytes / sec", bytes(d.sendBytesPerSecond) + "/s"],
            ["Receive bytes / sec", bytes(d.receiveBytesPerSecond) + "/s"], ["Retries", num(d.retryCount)],
            ["Queue overflow", num(d.queueOverflow)], ["Dropped", num(d.dropped)],
            ["Coalesced", num(d.coalesced)], ["Scenario paused", d.scenarioPaused ? "yes" : "no"]
        ];
        el.raw.innerHTML = cards.map((c) => "<article class=\"raw-card\"><span class=\"raw-card__label\">" + esc(c[0]) + "</span><strong class=\"raw-card__value\">" + esc(c[1]) + "</strong></article>").join("");
    }

    function renderPayloadMetadata() {
        const payload = state.payloadMeta;
        el.payloadDetails.innerHTML = "";
        if (!payload) {
            el.payloadMeta.textContent = "Waiting for payload samples";
            el.payloadDetails.appendChild(empty("Live payload metadata will appear here once traffic is observed."));
            el.payloadPreview.textContent = "No payload preview yet.";
            return;
        }
        const cards = [
            ["Namespace", payload.namespace || "n/a"],
            ["Topic", payload.topic || "n/a"],
            ["Direction", payload.direction || "n/a"],
            ["Process", payload.processName || "n/a"],
            ["Sent By", payload.sender || "n/a"],
            ["Received By", payload.receiver || "n/a"],
            ["Payload Kind", payload.kind || "unknown"],
            ["Source", payload.source || "n/a"],
            ["Declared Size", payload.declaredSizeBytes != null ? bytes(payload.declaredSizeBytes) : "n/a"],
            ["Preview Size", payload.previewSize],
            ["Content Type", payload.contentType || "n/a"],
            ["Keys", payload.keys && payload.keys.length ? payload.keys.join(", ") : "n/a"]
        ];
        el.payloadDetails.innerHTML = cards.map((c) => stat(c[0], c[1])).join("");
        el.payloadMeta.textContent = "Last seen " + stamp(payload.timestamp);
        el.payloadPreview.textContent = payload.preview || "No payload preview yet.";
    }

    function renderProcesses() {
        el.processes.innerHTML = "";
        const list = state.dashboard.processes || [];
        if (!list.length) return el.processes.appendChild(empty("No process state reported yet"));
        list.forEach((p) => {
            const card = document.createElement("article");
            card.className = "process-card";
            card.innerHTML = "<div><p class=\"process-card__title\">" + esc(p.name) + "</p><p class=\"process-card__meta\">" + esc(p.role) + "</p></div>"
                + "<div class=\"process-card__chips\">" + chip(p.alive ? "alive" : "stopped", p.alive ? "good" : "warn")
                + chip(p.ready ? "ready" : "warming", p.ready ? "good" : "warn") + "</div>";
            el.processes.appendChild(card);
        });
    }

    function renderControls() {
        el.namespace.innerHTML = "";
        el.persona.innerHTML = "";
        (state.config.namespaces || []).forEach((ns) => {
            const option = document.createElement("option");
            option.value = ns.namespace; option.textContent = ns.namespace; el.namespace.appendChild(option);
        });
        (state.config.personas || []).forEach((persona) => {
            const option = document.createElement("option");
            option.value = persona.name; option.textContent = persona.name + " (" + persona.connectionCount + ")"; el.persona.appendChild(option);
        });
        renderTopics();
    }

    function renderTopics() {
        el.topic.innerHTML = "";
        const ns = (state.config.namespaces || []).find((item) => item.namespace === el.namespace.value) || (state.config.namespaces || [])[0];
        (ns && ns.topics ? ns.topics : ["prices"]).forEach((topic) => {
            const option = document.createElement("option");
            option.value = topic; option.textContent = topic; el.topic.appendChild(option);
        });
    }

    function openEvents() {
        if (state.source) state.source.close();
        state.source = new EventSource("/events");
        state.source.onopen = () => {
            if (state.sseRetry) {
                window.clearTimeout(state.sseRetry);
                state.sseRetry = null;
            }
            status(el.connection, "Event stream connected", "green");
        };
        state.source.onerror = () => {
            status(el.connection, "Event stream reconnecting", "amber");
            if (state.sseRetry || !state.source || state.source.readyState !== EventSource.CLOSED) return;
            state.sseRetry = window.setTimeout(() => {
                state.sseRetry = null;
                openEvents();
            }, SSE_RETRY_MS);
        };
        STREAM_EVENTS.forEach((eventName) => {
            state.source.addEventListener(eventName, handleStreamEvent);
        });
    }

    function handleStreamEvent(event) {
        const data = parse(event.data);
        capturePayloadMetadata(data);
        timeline(data.summary || event.data, data.category || event.type || "event", accent(data.category || event.type), data.timestamp, data);
    }

    async function runAction(name) {
        try {
            if (name === "targeted-sample" && !el.targetClient.value.trim()) throw new Error("Enter a target client id first");
            if (name === "restart-persona" && !el.persona.value) throw new Error("Select a persona first");
            const body = name === "targeted-sample" ? { clientId: el.targetClient.value.trim() }
                : name === "restart-persona" ? { persona: el.persona.value } : {};
            await json("/api/actions/" + name, { method: "POST", body: JSON.stringify(body) });
            timeline("Launcher action " + name + " accepted", name, "amber");
            await refreshDashboard();
            await refreshConfig();
        } catch (error) {
            timeline("Launcher action failed: " + error.message, "error", "red");
            status(el.runtime, "Action failed", "red");
        }
    }

    function connectManual() {
        if (!window.io) { timeline("Socket.IO browser client is unavailable", "error", "red"); return; }
        disconnectManual(false);
        const token = el.token.value.trim();
        const socket = window.io(state.config.socketUrl + selectedNs(), { transports: ["websocket"], reconnection: false, query: token ? { token } : {} });
        socket.on("connect", () => { status(el.manualStatus, "Browser client connected", "green"); timeline("Browser client connected to " + selectedNs(), "manual-connect", "green"); });
        socket.on("disconnect", () => { status(el.manualStatus, "Manual browser client idle", "neutral"); timeline("Browser client disconnected", "manual-disconnect", "amber"); });
        socket.on("topic-message", (envelope) => {
            const meta = envelope.metadata || {};
            const payload = envelope.payload || {};
            const payloadMeta = payloadMetadataFromValue(payload);
            const event = {
                timestamp: new Date().toISOString(),
                processName: "browser-console",
                direction: "inbound",
                namespace: selectedNs(),
                topic: meta.topic || selectedTopic(),
                sender: payloadMeta.source || "server",
                receiver: "browser-console",
                payloadSource: payloadMeta.source,
                payloadContentType: payloadMeta.contentType,
                payloadDeclaredSizeBytes: payloadMeta.declaredSizeBytes,
                payloadPreview: JSON.stringify(payload)
            };
            capturePayloadMetadata(event);
            timeline("Manual client received " + (meta.topic || "topic-message"), "topic-message", "green", event.timestamp, event);
            if (meta.ackRequired && meta.topic && meta.messageId) socket.emit("ack", { topic: meta.topic, messageId: meta.messageId });
        });
        socket.on("subscribed", (payload) => timeline("Subscribed to " + payload.topic, "subscribed", "blue"));
        socket.on("unsubscribed", (payload) => timeline("Unsubscribed from " + payload.topic, "unsubscribed", "amber"));
        socket.on("error", (payload) => timeline("Manual socket error: " + JSON.stringify(payload), "error", "red"));
        state.manualSocket = socket;
    }

    function disconnectManual(announce) {
        if (!state.manualSocket) return;
        state.manualSocket.disconnect();
        if (typeof state.manualSocket.close === "function") state.manualSocket.close();
        state.manualSocket = null;
        if (announce) timeline("Manual browser client disconnected", "manual-disconnect", "amber");
        status(el.manualStatus, "Manual browser client idle", "neutral");
    }

    function subscribeManual() {
        if (!state.manualSocket) return timeline("Connect the browser client before subscribing", "error", "red");
        state.manualSocket.emit("subscribe", req({}));
        timeline("Subscribe requested for " + selectedTopic(), "subscribe", "blue");
    }

    function unsubscribeManual() {
        if (!state.manualSocket) return timeline("Connect the browser client before unsubscribing", "error", "red");
        state.manualSocket.emit("unsubscribe", { topic: selectedTopic() });
        timeline("Unsubscribe requested for " + selectedTopic(), "unsubscribe", "amber");
    }

    function publishManual() {
        if (!state.manualSocket) return timeline("Connect the browser client before publishing", "error", "red");
        try {
            const payload = JSON.parse(el.payload.value);
            const payloadMeta = payloadMetadataFromValue(payload);
            const event = {
                timestamp: new Date().toISOString(),
                processName: "browser-console",
                direction: "outbound",
                namespace: selectedNs(),
                topic: selectedTopic(),
                sender: "browser-console",
                receiver: "server",
                payloadSource: payloadMeta.source,
                payloadContentType: payloadMeta.contentType,
                payloadDeclaredSizeBytes: payloadMeta.declaredSizeBytes,
                payloadPreview: JSON.stringify(payload)
            };
            capturePayloadMetadata(event);
            state.manualSocket.emit("publish", req({ payload }));
            timeline("Browser publish sent to " + selectedTopic(), "publish", "amber", event.timestamp, event);
        } catch (error) {
            timeline("Payload must be valid JSON", "error", "red");
        }
    }

    function publishBulkManual() {
        el.namespace.value = "/bulk";
        renderTopics();
        el.payload.value = JSON.stringify({ blob: "AA==", declaredSizeBytes: 524288, contentType: "application/octet-stream", source: "browser-console" }, null, 2);
        publishManual();
    }

    function req(extra) {
        const payload = Object.assign({ topic: selectedTopic() }, extra);
        const token = el.token.value.trim();
        if (token) payload.token = token;
        return payload;
    }

    function renderChart(target, history, series) {
        target.innerHTML = "";
        const list = (history || []).filter((item) => item && item.capturedAt);
        const w = 520, h = 180, p = 16;
        for (let i = 0; i < 4; i++) {
            const y = p + ((h - p * 2) / 3) * i;
            line(target, p, y, w - p, y, "rgba(31,33,35,0.1)");
        }
        if (!list.length) {
            const text = document.createElementNS(NS, "text");
            text.setAttribute("x", String(w / 2)); text.setAttribute("y", String(h / 2)); text.setAttribute("text-anchor", "middle"); text.setAttribute("fill", "#6a6660");
            text.textContent = "Waiting for samples"; target.appendChild(text); return;
        }
        const max = Math.max(1, ...list.flatMap((item) => series.map((s) => Number(item[s.key] || 0))));
        series.forEach((s) => {
            const poly = document.createElementNS(NS, "polyline");
            const points = list.map((item, i) => {
                const x = p + ((w - p * 2) * i / Math.max(1, list.length - 1));
                const y = h - p - ((h - p * 2) * Number(item[s.key] || 0) / max);
                return x.toFixed(2) + "," + y.toFixed(2);
            }).join(" ");
            poly.setAttribute("points", points); poly.setAttribute("fill", "none"); poly.setAttribute("stroke", s.color); poly.setAttribute("stroke-width", "3");
            poly.setAttribute("stroke-linejoin", "round"); poly.setAttribute("stroke-linecap", "round"); target.appendChild(poly);
        });
    }

    function line(target, x1, y1, x2, y2, stroke) {
        const node = document.createElementNS(NS, "line");
        node.setAttribute("x1", String(x1)); node.setAttribute("y1", String(y1)); node.setAttribute("x2", String(x2)); node.setAttribute("y2", String(y2));
        node.setAttribute("stroke", stroke); node.setAttribute("stroke-width", "1"); target.appendChild(node);
    }

    function capturePayloadMetadata(event) {
        if (!event || !event.payloadPreview) return;
        if (event.category && event.category !== "publish" && event.category !== "topic_message") return;
        const preview = String(event.payloadPreview);
        const parsed = parsePayloadPreview(preview);
        const declaredSizeBytes = event.payloadDeclaredSizeBytes != null
            ? Number(event.payloadDeclaredSizeBytes)
            : parsed.declaredSizeBytes;
        const contentType = event.payloadContentType || parsed.contentType;
        const source = event.payloadSource || parsed.source || event.sender || null;
        state.payloadMeta = {
            timestamp: event.timestamp || new Date().toISOString(),
            processName: event.processName || "unknown",
            direction: event.direction || "event",
            namespace: event.namespace || null,
            topic: event.topic || null,
            sender: event.sender || source || null,
            receiver: event.receiver || (event.direction === "inbound" ? event.processName : null),
            kind: contentType === "application/octet-stream" || declaredSizeBytes != null ? "BINARY" : parsed.kind,
            source,
            contentType,
            declaredSizeBytes,
            keys: parsed.keys,
            previewSize: parsed.previewSize,
            preview
        };
        renderPayloadMetadata();
    }

    function payloadMetadataFromValue(payload) {
        if (!payload || typeof payload !== "object") {
            return { source: null, contentType: null, declaredSizeBytes: null };
        }
        const context = payload.context && typeof payload.context === "object" ? payload.context : null;
        return {
            source: payload.source || (context && context.source) || null,
            contentType: payload.contentType || null,
            declaredSizeBytes: typeof payload.declaredSizeBytes === "number" ? payload.declaredSizeBytes : null
        };
    }

    function parsePayloadPreview(preview) {
        try {
            const parsed = JSON.parse(preview);
            const keys = Object.keys(parsed).slice(0, 8);
            return {
                kind: parsed.blob || parsed.contentType === "application/octet-stream" || parsed.declaredSizeBytes ? "BINARY" : "JSON",
                source: parsed.source || (parsed.context && parsed.context.source) || null,
                contentType: parsed.contentType || null,
                declaredSizeBytes: typeof parsed.declaredSizeBytes === "number" ? parsed.declaredSizeBytes : null,
                keys,
                previewSize: bytes(preview.length)
            };
        } catch (error) {
            const keys = [...preview.matchAll(/"([^"]+)"\s*:/g)].map((match) => match[1]).filter(unique).slice(0, 8);
            const source = matchValue(preview, /"source"\s*:\s*"([^"]+)"/);
            const contentType = matchValue(preview, /"contentType"\s*:\s*"([^"]+)"/);
            const declaredSize = matchNumber(preview, /"declaredSizeBytes"\s*:\s*(\d+)/);
            return {
                kind: keys.includes("blob") || contentType === "application/octet-stream" || declaredSize != null ? "BINARY" : keys.length ? "JSON" : "TEXT",
                source,
                contentType,
                declaredSizeBytes: declaredSize,
                keys,
                previewSize: bytes(preview.length)
            };
        }
    }

    function matchValue(value, pattern) {
        const match = value.match(pattern);
        return match ? match[1] : null;
    }

    function matchNumber(value, pattern) {
        const match = value.match(pattern);
        return match ? Number(match[1]) : null;
    }

    function unique(value, index, array) {
        return array.indexOf(value) === index;
    }

    async function json(url, options) {
        const response = await fetch(url, Object.assign({ headers: { Accept: "application/json", "Content-Type": "application/json" } }, options || {}));
        const body = await response.json();
        if (!response.ok) throw new Error(body.message || body.status || "Request failed");
        return body;
    }

    function timeline(summary, category, variant, timestamp, data) {
        if (HIGH_FREQUENCY_CATEGORIES.has(category || "")) {
            sampleTimelineEvent(summary, category, variant, timestamp, data);
            return;
        }
        flushSampledTimeline();
        appendTimeline(summary, category, variant, timestamp, data);
    }

    function sampleTimelineEvent(summary, category, variant, timestamp, data) {
        const details = data || {};
        const key = [
            category || "event",
            details.namespace || "",
            details.topic || "",
            details.direction || "",
            details.sender || details.payloadSource || "",
            details.receiver || ""
        ].join("|");
        const existing = state.timelineBuckets.get(key);
        if (existing) {
            existing.count += 1;
            existing.timestamp = timestamp || existing.timestamp;
            existing.summary = summary || existing.summary;
            existing.data = Object.assign({}, existing.data, details);
            return;
        }
        state.timelineBuckets.set(key, {
            count: 1,
            category: category || "event",
            variant: variant || accent(category),
            timestamp: timestamp || new Date().toISOString(),
            summary: summary || category || "event",
            data: Object.assign({}, details)
        });
    }

    function flushSampledTimeline() {
        if (!state.timelineBuckets.size) {
            return;
        }
        const buckets = Array.from(state.timelineBuckets.values())
            .sort((left, right) => String(left.timestamp).localeCompare(String(right.timestamp)));
        state.timelineBuckets.clear();
        buckets.forEach((bucket) => {
            const label = bucket.category === "topic_message" ? "topic_message_sample" : "publish_sample";
            const kind = bucket.category === "topic_message" ? "topic-message" : "publish";
            const target = bucket.data.topic ? " on " + bucket.data.topic : "";
            const summary = num(bucket.count) + " " + kind + " events sampled" + target;
            const data = Object.assign({}, bucket.data, { sampleCount: bucket.count });
            appendTimeline(summary, label, bucket.variant, bucket.timestamp, data);
        });
    }

    function appendTimeline(summary, category, variant, timestamp, data) {
        const item = document.createElement("article");
        item.className = "timeline-item timeline-item--" + (variant || "neutral");
        item.innerHTML = "<div class=\"timeline-item__header\"><span class=\"timeline-item__category\">" + esc(category || "event") + "</span><time>" + esc(stamp(timestamp)) + "</time></div>"
            + "<p class=\"timeline-item__summary\">" + esc(summary || "") + "</p>"
            + timelineDetails(data);
        el.timeline.prepend(item);
        while (el.timeline.children.length > MAX_EVENTS) el.timeline.removeChild(el.timeline.lastElementChild);
        el.timelineMeta.textContent = el.timeline.children.length + " events retained, high-frequency publish/topic-message traffic is sampled";
    }

    function timelineDetails(data) {
        if (!data || typeof data !== "object") return "";
        const details = [
            ["Sampled", data.sampleCount ? num(data.sampleCount) + " events" : null],
            ["Topic", data.topic],
            ["Namespace", data.namespace],
            ["Sent By", data.sender || data.payloadSource],
            ["Received By", data.receiver || (data.direction === "inbound" ? data.processName : null)],
            ["Client", data.clientId],
            ["Content", data.payloadContentType],
            ["Declared", data.payloadDeclaredSizeBytes != null ? bytes(data.payloadDeclaredSizeBytes) : null]
        ].filter((entry) => entry[1]);
        if (!details.length) {
            return "";
        }
        return "<dl class=\"timeline-item__details\">"
            + details.map((entry) => "<div class=\"timeline-detail\"><dt>" + esc(entry[0]) + "</dt><dd>" + esc(String(entry[1])) + "</dd></div>").join("")
            + "</dl>";
    }

    function empty(text) { const node = document.createElement("p"); node.className = "empty-state"; node.textContent = text; return node; }
    function li(text) { return "<li>" + esc(text) + "</li>"; }
    function stat(label, value) { return "<div class=\"stat\"><span class=\"stat__label\">" + esc(label) + "</span><span class=\"stat__value\">" + esc(String(value)) + "</span></div>"; }
    function chip(label, variant) { return "<span class=\"chip chip--" + esc(variant) + "\">" + esc(label) + "</span>"; }
    function sumNs(key) { return (state.dashboard.namespaces || []).reduce((sum, ns) => sum + Number(ns[key] || 0), 0); }
    function selectedNs() { return el.namespace.value || "/non-reliable"; }
    function selectedTopic() { return el.topic.value || "prices"; }
    function status(target, text, variant) { target.textContent = text; target.className = "status-pill status-pill--" + variant; }
    function num(value) { return new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 }).format(Number(value || 0)); }
    function rate(value, unit) { return new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 }).format(Number(value || 0)) + " " + unit; }
    function bytes(value) { const n = Number(value || 0); return n >= 1048576 ? (n / 1048576).toFixed(2) + " MiB" : n >= 1024 ? (n / 1024).toFixed(1) + " KiB" : n.toFixed(0) + " B"; }
    function formatMs(value) {
        const n = Number(value || 0);
        if (!n) return "not used";
        if (n % 1000 === 0) return (n / 1000) + " s";
        return n + " ms";
    }
    function stamp(value) { return value ? new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }) : new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }); }
    function parse(value) { try { return JSON.parse(value); } catch (error) { return { summary: value }; } }
    function accent(category) { return /error/i.test(category || "") ? "red" : /ready|topic|accepted/i.test(category || "") ? "green" : /client|subscribe|ack/i.test(category || "") ? "blue" : /publish|action|preset|pause|resume/i.test(category || "") ? "amber" : "neutral"; }
    function esc(value) { return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\"", "&quot;").replaceAll("'", "&#39;"); }
})();

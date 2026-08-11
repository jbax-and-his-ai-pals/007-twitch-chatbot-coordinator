const feedEl = document.getElementById("feed");
const botCardsEl = document.getElementById("botCards");
const mockSwitch = document.getElementById("mockSwitch");
const mockLabel = document.getElementById("mockLabel");
const connStatus = document.getElementById("connStatus");
const showAiCalls = document.getElementById("showAiCalls");
const showDebugLog = document.getElementById("showDebugLog");
const overridesListEl = document.getElementById("overridesList");

const COOLDOWN_TYPES = ["mainline", "wisdom", "callout", "retry", "request"];
const RECENT_MESSAGES_LIMIT = 8;
const LOG_LEVEL_CLASS = {
  error: "tag-log-error",
  warn: "tag-log-warn",
  success: "tag-log-success",
  callout: "tag-log-callout",
  highlight: "tag-log-highlight",
  info: "tag-log-info",
  message: "tag-log-message",
};
let bots = [];
const recentByBot = new Map();

async function api(path, options) {
  const res = await fetch(`/api${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

function timeStr(ms) {
  const d = new Date(ms);
  return d.toLocaleTimeString([], { hour12: false });
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);
}

function recordOutgoing(evt) {
  const list = recentByBot.get(evt.botKey) || [];
  list.push(evt);
  while (list.length > RECENT_MESSAGES_LIMIT) list.shift();
  recentByBot.set(evt.botKey, list);
  renderRecentMessages(evt.botKey);
}

function renderRecentMessages(botKey) {
  const card = botCardsEl.querySelector(`[data-bot-key="${botKey}"]`);
  const container = card && card.querySelector(".recent-messages");
  if (!container) return;

  const list = recentByBot.get(botKey) || [];
  if (list.length === 0) {
    container.innerHTML = `<div class="recent-empty">no messages yet</div>`;
    return;
  }
  container.innerHTML = list.slice().reverse().map((evt) => `
    <div class="recent-message${evt.manual ? " recent-manual" : ""}">
      <span class="recent-time">${timeStr(evt.timestampMs)}</span>${escapeHtml(evt.message)}
    </div>`).join("");
}

function appendFeedEntry(evt) {
  if (evt.type === "ai_call" && !showAiCalls.checked) return;
  if (evt.type === "log" && !showDebugLog.checked) return;
  if (evt.type === "outgoing") recordOutgoing(evt);

  const atBottom = feedEl.scrollHeight - feedEl.scrollTop - feedEl.clientHeight < 40;
  const row = document.createElement("div");
  row.className = "feed-entry";

  if (evt.type === "incoming") {
    const tagClass = evt.simulated ? "tag-simulated" : "tag-incoming";
    const tagText = evt.simulated ? "SIMULATED" : "CHAT";
    row.innerHTML = `<span class="feed-time">${timeStr(evt.timestampMs)}</span>` +
      `<span class="feed-tag ${tagClass}">${tagText}</span>` +
      `<span class="feed-user">${escapeHtml(evt.user)}</span>: ${escapeHtml(evt.message)}`;
  } else if (evt.type === "outgoing") {
    const tagClass = evt.manual ? "tag-manual" : "tag-outgoing";
    const tagText = evt.manual ? "MANUAL" : "BOT";
    row.innerHTML = `<span class="feed-time">${timeStr(evt.timestampMs)}</span>` +
      `<span class="feed-tag ${tagClass}">${tagText}</span>` +
      `<span class="feed-bot">${escapeHtml(evt.botKey)}</span>: ${escapeHtml(evt.message)}`;
  } else if (evt.type === "ai_call") {
    row.innerHTML = `<span class="feed-time">${timeStr(evt.timestampMs)}</span>` +
      `<span class="feed-tag tag-ai">${evt.mocked ? "MOCK AI" : "REAL AI"}</span>` +
      `<span class="feed-bot">${escapeHtml(evt.botKey)}</span>` +
      `<span class="feed-ai-detail">prompt: ${escapeHtml(evt.prompt.slice(0, 200))}</span>` +
      `<span class="feed-ai-detail">reply: ${escapeHtml(evt.response)}</span>`;
  } else if (evt.type === "log") {
    const cls = LOG_LEVEL_CLASS[evt.level] || "tag-log-message";
    row.classList.add("feed-entry-log");
    row.innerHTML = `<span class="feed-time">${timeStr(evt.timestampMs)}</span>` +
      `<span class="feed-tag ${cls}">LOG</span>` +
      `<span class="feed-log-text">${escapeHtml(evt.message)}</span>`;
  }

  feedEl.appendChild(row);
  while (feedEl.childElementCount > 500) feedEl.removeChild(feedEl.firstChild);
  if (atBottom) feedEl.scrollTop = feedEl.scrollHeight;
}

document.getElementById("clearFeed").addEventListener("click", () => {
  feedEl.innerHTML = "";
});

document.getElementById("simulateForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const userInput = document.getElementById("simulateUser");
  const messageInput = document.getElementById("simulateMessage");
  const user = userInput.value.trim();
  const message = messageInput.value.trim();
  if (!user || !message) return;
  await api("/simulate", { method: "POST", body: JSON.stringify({ user, message }) });
  messageInput.value = "";
});

function connectWebSocket() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/ws/activity`);

  ws.onopen = () => connStatus.classList.add("live");
  ws.onclose = () => {
    connStatus.classList.remove("live");
    setTimeout(connectWebSocket, 2000);
  };
  ws.onerror = () => ws.close();
  ws.onmessage = (msg) => {
    try {
      appendFeedEntry(JSON.parse(msg.data));
    } catch (e) {
      console.error("bad activity event", e);
    }
  };
}

function cooldownBarHtml(botKey, type, status) {
  // The snapshot only reports remaining time, not the configured interval, so the bar
  // is a simple ready/not-ready indicator rather than a proportional countdown.
  const seconds = (status.remainingMs / 1000).toFixed(1);
  return `
    <div class="cooldown-row" data-bot="${botKey}" data-type="${type}">
      <span class="cooldown-name">${type}</span>
      <div class="cooldown-bar-track"><div class="cooldown-bar-fill ${status.ready ? "ready" : ""}" style="width:${status.ready ? 0 : 100}%"></div></div>
      <span class="cooldown-val">${status.ready ? "ready" : seconds + "s"}</span>
      <button class="cooldown-expire" title="Expire now">&#9199;</button>
    </div>`;
}

function renderBotCard(bot) {
  const card = document.createElement("div");
  card.className = "bot-card";
  card.dataset.botKey = bot.key;
  card.innerHTML = `
    <h3>${escapeHtml(bot.key)}</h3>
    <div class="bot-meta">${escapeHtml(bot.accountUsername)} &middot; mood: ${escapeHtml(bot.activeMood)} &middot; ${escapeHtml(bot.channel)}</div>
    <div class="cooldowns"></div>
    <form class="send-form">
      <input type="text" placeholder="Send as ${escapeHtml(bot.key)}..." />
      <button type="submit">Send</button>
    </form>
    <div class="recent-header">Recent messages</div>
    <div class="recent-messages"><div class="recent-empty">no messages yet</div></div>
    <button class="config-toggle" data-bot="${escapeHtml(bot.key)}">&#9881; Configure</button>
    <div class="config-panel" style="display:none"></div>
  `;

  card.querySelector("form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const input = card.querySelector("input");
    const message = input.value.trim();
    if (!message) return;
    await api("/send", { method: "POST", body: JSON.stringify({ botKey: bot.key, message }) });
    input.value = "";
  });

  card.querySelector(".config-toggle").addEventListener("click", async () => {
    const panel = card.querySelector(".config-panel");
    const opening = panel.style.display === "none";
    panel.style.display = opening ? "block" : "none";
    if (opening && !panel.dataset.loaded) await refreshConfigPanel(bot.key);
  });

  return card;
}

// ---- Config panel: read-only display + editable lists/numbers/mood ----------------

function configSectionHtml(botKey, config, cooldowns) {
  return `
    <div class="config-block">
      <h4>Identity</h4>
      <div class="config-readonly">
        <div><b>Trigger keywords:</b> ${config.triggerKeywords.map(escapeHtml).join(", ")}</div>
        <div><b>Transform words:</b> ${config.transformWords.map(escapeHtml).join(", ") || "(none)"}</div>
        <div><b>Self-quote marker:</b> ${escapeHtml(config.selfQuoteMarker || "(none)")}</div>
        ${config.helloTriggers.length ? `<div><b>Hello triggers:</b><ul>${config.helloTriggers.map((t) => `<li>${escapeHtml(t)}</li>`).join("")}</ul></div>` : ""}
        ${config.features.length ? `<div><b>Features:</b><ul>${config.features.map((f) => `<li>${escapeHtml(f)}</li>`).join("")}</ul></div>` : ""}
      </div>
    </div>

    <div class="config-block">
      <h4>Active mood</h4>
      <select class="config-mood-select" data-bot="${escapeHtml(botKey)}">
        ${config.moods.map((m) => `<option value="${escapeHtml(m)}" ${m === config.activeMood ? "selected" : ""}>${escapeHtml(m)}</option>`).join("")}
      </select>
    </div>

    ${chipListBlockHtml(botKey, "Excluded users", "excluded-users", "user", config.excludedUsers)}
    ${chipListBlockHtml(botKey, "Callout words", "callout-words", "word", config.calloutWords)}
    ${chipListBlockHtml(botKey, "Forbidden words", "forbidden-words", "word", config.forbiddenWords)}
    ${overrideListBlockHtml(botKey, config.calloutResponseOverrides)}

    <div class="config-block">
      <h4>Message length</h4>
      <div class="config-numfields">
        <label>min <input type="number" class="cfg-num" data-field="msg-min" value="${config.messageLengthMinimum}" /></label>
        <label>max <input type="number" class="cfg-num" data-field="msg-max" value="${config.messageLengthMaximum}" /></label>
        <button data-action="save-message-length" data-bot="${escapeHtml(botKey)}">Save</button>
      </div>
    </div>

    <div class="config-block">
      <h4>Similarity threshold</h4>
      <div class="config-numfields">
        <input type="number" class="cfg-num" data-field="similarity" value="${config.similarityThreshold}" />
        <button data-action="save-similarity" data-bot="${escapeHtml(botKey)}">Save</button>
      </div>
    </div>

    <div class="config-block">
      <h4>Minimum messages between mainline actions</h4>
      <div class="config-numfields">
        <input type="number" class="cfg-num" data-field="min-messages" value="${config.minimumMessagesBetween}" />
        <button data-action="save-min-messages" data-bot="${escapeHtml(botKey)}">Save</button>
      </div>
    </div>

    <div class="config-block">
      <h4>Cooldown durations (ms)</h4>
      <div class="config-numfields cooldown-durations">
        <label>wisdom <input type="number" class="cfg-num" data-field="cd-wisdom" value="${cooldowns.wisdom.configuredMs}" /></label>
        <label>callout <input type="number" class="cfg-num" data-field="cd-callout" value="${cooldowns.callout.configuredMs}" /></label>
        <label>retry <input type="number" class="cfg-num" data-field="cd-retry" value="${cooldowns.retry.configuredMs}" /></label>
        <label>request <input type="number" class="cfg-num" data-field="cd-request" value="${cooldowns.request.configuredMs}" /></label>
        <button data-action="save-cooldown-durations" data-bot="${escapeHtml(botKey)}">Save</button>
      </div>
    </div>

    <div class="config-block">
      <h4>Mainline interval bounds (ms)</h4>
      <div class="config-numfields">
        <label>min <input type="number" class="cfg-num" data-field="mainline-min" value="${cooldowns.mainlineIntervalMinimumMs}" /></label>
        <label>max <input type="number" class="cfg-num" data-field="mainline-max" value="${cooldowns.mainlineIntervalMaximumMs}" /></label>
        <button data-action="save-mainline-interval" data-bot="${escapeHtml(botKey)}">Save</button>
      </div>
    </div>
  `;
}

function chipListBlockHtml(botKey, title, listKey, fieldName, items) {
  const chips = items.map((item) => `
    <span class="chip">${escapeHtml(item)}<button data-action="remove-chip" data-bot="${escapeHtml(botKey)}" data-list="${listKey}" data-value="${escapeHtml(item)}">&times;</button></span>
  `).join("") || `<span class="config-empty">none</span>`;

  return `
    <div class="config-block">
      <h4>${title}</h4>
      <div class="chip-list">${chips}</div>
      <div class="config-add-form">
        <input type="text" class="chip-input" placeholder="add..." />
        <button data-action="add-chip" data-bot="${escapeHtml(botKey)}" data-list="${listKey}" data-field="${fieldName}">Add</button>
      </div>
    </div>
  `;
}

function overrideListBlockHtml(botKey, overrides) {
  const rows = Object.entries(overrides).map(([word, response]) => `
    <div class="override-row-cfg">
      <span>"${escapeHtml(word)}" &rarr; ${escapeHtml(response)}</span>
      <button data-action="remove-override" data-bot="${escapeHtml(botKey)}" data-value="${escapeHtml(word)}">&times;</button>
    </div>
  `).join("") || `<span class="config-empty">none</span>`;

  return `
    <div class="config-block">
      <h4>Callout response overrides</h4>
      <div class="override-list-cfg">${rows}</div>
      <div class="config-add-form">
        <input type="text" class="override-word-input" placeholder="word" />
        <input type="text" class="override-response-input" placeholder="response" />
        <button data-action="add-override" data-bot="${escapeHtml(botKey)}">Add</button>
      </div>
    </div>
  `;
}

async function refreshConfigPanel(botKey) {
  const card = botCardsEl.querySelector(`[data-bot-key="${botKey}"]`);
  const panel = card && card.querySelector(".config-panel");
  if (!panel) return;
  const [config, cooldowns] = await Promise.all([
    api(`/bots/${encodeURIComponent(botKey)}/config`),
    api(`/cooldowns/${encodeURIComponent(botKey)}`),
  ]);
  panel.innerHTML = configSectionHtml(botKey, config, cooldowns);
  panel.dataset.loaded = "true";
}

async function refreshBotMeta(botKey) {
  const freshBots = await api("/bots");
  bots = freshBots;
  const fresh = freshBots.find((b) => b.key === botKey);
  const card = botCardsEl.querySelector(`[data-bot-key="${botKey}"]`);
  const metaEl = card && card.querySelector(".bot-meta");
  if (fresh && metaEl) {
    metaEl.innerHTML = `${escapeHtml(fresh.accountUsername)} &middot; mood: ${escapeHtml(fresh.activeMood)} &middot; ${escapeHtml(fresh.channel)}`;
  }
}

botCardsEl.addEventListener("click", async (e) => {
  const target = e.target.closest("[data-action]");
  if (!target) return;
  const botKey = target.dataset.bot;
  const panel = target.closest(".config-panel");
  const block = target.closest(".config-block");

  switch (target.dataset.action) {
    case "remove-chip":
      await api(`/bots/${encodeURIComponent(botKey)}/${target.dataset.list}/${encodeURIComponent(target.dataset.value)}`, { method: "DELETE" });
      await refreshConfigPanel(botKey);
      break;
    case "add-chip": {
      const input = block.querySelector(".chip-input");
      const value = input.value.trim();
      if (!value) return;
      await api(`/bots/${encodeURIComponent(botKey)}/${target.dataset.list}`, {
        method: "POST",
        body: JSON.stringify({ [target.dataset.field]: value }),
      });
      await refreshConfigPanel(botKey);
      break;
    }
    case "remove-override":
      await api(`/bots/${encodeURIComponent(botKey)}/callout-overrides/${encodeURIComponent(target.dataset.value)}`, { method: "DELETE" });
      await refreshConfigPanel(botKey);
      break;
    case "add-override": {
      const word = block.querySelector(".override-word-input").value.trim();
      const response = block.querySelector(".override-response-input").value.trim();
      if (!word || !response) return;
      await api(`/bots/${encodeURIComponent(botKey)}/callout-overrides`, { method: "POST", body: JSON.stringify({ word, response }) });
      await refreshConfigPanel(botKey);
      break;
    }
    case "save-message-length": {
      const minimum = Number(panel.querySelector('[data-field="msg-min"]').value);
      const maximum = Number(panel.querySelector('[data-field="msg-max"]').value);
      await api(`/bots/${encodeURIComponent(botKey)}/message-length`, { method: "POST", body: JSON.stringify({ minimum, maximum }) });
      await refreshConfigPanel(botKey);
      break;
    }
    case "save-similarity": {
      const threshold = Number(panel.querySelector('[data-field="similarity"]').value);
      await api(`/bots/${encodeURIComponent(botKey)}/similarity-threshold`, { method: "POST", body: JSON.stringify({ threshold }) });
      await refreshConfigPanel(botKey);
      break;
    }
    case "save-min-messages": {
      const count = Number(panel.querySelector('[data-field="min-messages"]').value);
      await api(`/bots/${encodeURIComponent(botKey)}/minimum-messages-between`, { method: "POST", body: JSON.stringify({ count }) });
      await refreshConfigPanel(botKey);
      break;
    }
    case "save-cooldown-durations": {
      const reads = {
        wisdom: Number(panel.querySelector('[data-field="cd-wisdom"]').value),
        callout: Number(panel.querySelector('[data-field="cd-callout"]').value),
        retry: Number(panel.querySelector('[data-field="cd-retry"]').value),
        request: Number(panel.querySelector('[data-field="cd-request"]').value),
      };
      await Promise.all(Object.entries(reads).map(([type, ms]) =>
        api(`/bots/${encodeURIComponent(botKey)}/cooldown-duration/${type}`, { method: "POST", body: JSON.stringify({ ms }) })
      ));
      await refreshConfigPanel(botKey);
      break;
    }
    case "save-mainline-interval": {
      const minMs = Number(panel.querySelector('[data-field="mainline-min"]').value);
      const maxMs = Number(panel.querySelector('[data-field="mainline-max"]').value);
      await api(`/bots/${encodeURIComponent(botKey)}/mainline-interval`, { method: "POST", body: JSON.stringify({ minMs, maxMs }) });
      await refreshConfigPanel(botKey);
      break;
    }
  }
});

botCardsEl.addEventListener("change", async (e) => {
  if (!e.target.classList.contains("config-mood-select")) return;
  const botKey = e.target.dataset.bot;
  const mood = e.target.value;
  await api(`/bots/${encodeURIComponent(botKey)}/active-mood`, { method: "POST", body: JSON.stringify({ mood }) });
  await Promise.all([refreshConfigPanel(botKey), refreshBotMeta(botKey)]);
});

async function refreshCooldowns() {
  for (const bot of bots) {
    const card = botCardsEl.querySelector(`[data-bot-key="${bot.key}"]`);
    if (!card) continue;
    try {
      const snapshot = await api(`/cooldowns/${encodeURIComponent(bot.key)}`);
      const container = card.querySelector(".cooldowns");
      container.innerHTML = COOLDOWN_TYPES.map((t) => cooldownBarHtml(bot.key, t, snapshot[t])).join("");
      container.querySelectorAll(".cooldown-expire").forEach((btn) => {
        btn.addEventListener("click", async () => {
          const row = btn.closest(".cooldown-row");
          await api(`/cooldowns/${encodeURIComponent(row.dataset.bot)}/${row.dataset.type}/expire`, { method: "POST" });
          refreshCooldowns();
        });
      });
    } catch (e) {
      console.error("cooldown refresh failed", e);
    }
  }
}

async function loadBots() {
  bots = await api("/bots");
  botCardsEl.innerHTML = "";
  bots.forEach((bot) => botCardsEl.appendChild(renderBotCard(bot)));
  bots.forEach((bot) => renderRecentMessages(bot.key));
  await refreshCooldowns();
}

async function loadInitialFeed() {
  const events = await api("/activity?limit=200");
  events.forEach(appendFeedEntry);
  feedEl.scrollTop = feedEl.scrollHeight;
}

async function loadMockMode() {
  const { enabled } = await api("/mock");
  mockSwitch.checked = enabled;
  mockLabel.textContent = enabled ? "MOCK MODE" : "REAL API (COSTS $)";
  mockLabel.classList.toggle("real", !enabled);
}

mockSwitch.addEventListener("change", async () => {
  await api("/mock", { method: "POST", body: JSON.stringify({ enabled: mockSwitch.checked }) });
  await loadMockMode();
});

async function loadOverrides() {
  const overrides = await api("/mock/overrides");
  overridesListEl.innerHTML = "";
  Object.entries(overrides).forEach(([keyword, response]) => {
    const li = document.createElement("li");
    li.innerHTML = `<span>"${escapeHtml(keyword)}" &rarr; ${escapeHtml(response)}</span>`;
    const removeBtn = document.createElement("button");
    removeBtn.textContent = "remove";
    removeBtn.addEventListener("click", async () => {
      await api(`/mock/overrides/${encodeURIComponent(keyword)}`, { method: "DELETE" });
      loadOverrides();
    });
    li.appendChild(removeBtn);
    overridesListEl.appendChild(li);
  });
}

document.getElementById("addOverride").addEventListener("click", async () => {
  const keywordInput = document.getElementById("overrideKeyword");
  const responseInput = document.getElementById("overrideResponse");
  const keyword = keywordInput.value.trim();
  const response = responseInput.value.trim();
  if (!keyword || !response) return;
  await api("/mock/overrides", { method: "POST", body: JSON.stringify({ keyword, response }) });
  keywordInput.value = "";
  responseInput.value = "";
  loadOverrides();
});

async function init() {
  await Promise.all([loadBots(), loadInitialFeed(), loadMockMode(), loadOverrides()]);
  // loadBots and loadInitialFeed race independently, so re-sync recent-message cards once
  // both have definitely finished, in case events arrived before their bot's card existed.
  bots.forEach((bot) => renderRecentMessages(bot.key));
  connectWebSocket();
  setInterval(refreshCooldowns, 1000);
}

init();

const feedEl = document.getElementById("feed");
const botCardsEl = document.getElementById("botCards");
const botsPanelEl = document.getElementById("botsPanel");
const mockSwitch = document.getElementById("mockSwitch");
const mockLabel = document.getElementById("mockLabel");
const modelSelect = document.getElementById("modelSelect");
const connStatus = document.getElementById("connStatus");
const filterChat = document.getElementById("filterChat");
const filterBot = document.getElementById("filterBot");
const filterAi = document.getElementById("filterAi");
const filterLog = document.getElementById("filterLog");
const filterWho = document.getElementById("filterWho");
const filterText = document.getElementById("filterText");
const filterLogLevel = document.getElementById("filterLogLevel");
const overridesListEl = document.getElementById("overridesList");
const historyDateSelect = document.getElementById("historyDateSelect");
const historyBanner = document.getElementById("historyBanner");
const historyBannerDate = document.getElementById("historyBannerDate");
const historyBackBtn = document.getElementById("historyBackBtn");

const COOLDOWN_TYPES = ["mainline", "wisdom", "callout", "retry", "request"];
const RECENT_MESSAGES_LIMIT = 8;
const ACTIVITY_HISTORY_LIMIT = 1000;
const LOG_TAG_CLASS = {
  error: "tag-log-error",
  warn: "tag-log-warn",
  success: "tag-log-success",
  callout: "tag-log-callout",
  highlight: "tag-log-highlight",
  info: "tag-log-info",
  message: "tag-log-message",
};
const LOG_TEXT_CLASS = {
  error: "log-text-error",
  warn: "log-text-warn",
  success: "log-text-success",
  callout: "log-text-callout",
  highlight: "log-text-highlight",
  info: "log-text-info",
  message: "log-text-message",
};
let bots = [];
const recentByBot = new Map();
const allEvents = [];
let viewingHistory = false;
let historyEvents = [];

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
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);
}

/** Deterministic hue (0-359) from a string's text - same text always gets the same color, different text usually doesn't. */
function hashHue(text) {
  let hash = 0;
  for (let i = 0; i < text.length; i++) {
    hash = (hash * 31 + text.charCodeAt(i)) | 0;
  }
  return ((hash % 360) + 360) % 360;
}

// ==================================================================================
// Reusable edit modal - click-to-edit for single strings, word pairs, and textareas.
// ==================================================================================

const editModal = document.getElementById("editModal");
const editModalTitle = document.getElementById("editModalTitle");
const editModalFields = document.getElementById("editModalFields");
const editModalDelete = document.getElementById("editModalDelete");
const editModalCancel = document.getElementById("editModalCancel");
const editModalSave = document.getElementById("editModalSave");

function closeEditModal() {
  editModal.style.display = "none";
  editModalSave.onclick = null;
  editModalDelete.onclick = null;
}

/**
 * fields: [{ id, label?, value, type: 'text'|'textarea' }]
 * onSave(values) - values is { [id]: string }
 * onDelete - omit to hide the Delete button entirely
 */
function openEditModal({ title, fields, onSave, onDelete }) {
  editModalTitle.textContent = title;
  editModalFields.innerHTML = fields.map((f) => `
    <label class="modal-field">
      ${f.label ? `<span>${escapeHtml(f.label)}</span>` : ""}
      ${f.type === "textarea"
        ? `<textarea data-field-id="${f.id}" rows="6">${escapeHtml(f.value || "")}</textarea>`
        : `<input type="text" data-field-id="${f.id}" value="${escapeHtml(f.value || "")}" />`}
    </label>
  `).join("");

  editModalDelete.style.display = onDelete ? "inline-block" : "none";
  editModal.style.display = "flex";

  const firstField = editModalFields.querySelector("input, textarea");
  if (firstField) firstField.focus();

  editModalSave.onclick = async () => {
    const values = {};
    fields.forEach((f) => {
      values[f.id] = editModalFields.querySelector(`[data-field-id="${f.id}"]`).value;
    });
    closeEditModal();
    await onSave(values);
  };

  editModalDelete.onclick = async () => {
    closeEditModal();
    if (onDelete) await onDelete();
  };
}

editModalCancel.addEventListener("click", closeEditModal);
editModal.addEventListener("click", (e) => {
  if (e.target === editModal) closeEditModal();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && editModal.style.display !== "none") closeEditModal();
});

// ==================================================================================
// Activity feed
// ==================================================================================

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
  container.innerHTML = list.slice().reverse().map((evt) => {
    const sourceHtml = evt.sourceUser
      ? `<div class="recent-source"><span class="recent-source-user">${escapeHtml(evt.sourceUser)}</span>: ${escapeHtml(evt.sourceMessage)}</div>`
      : `<div class="recent-source recent-source-manual">sent by operator</div>`;
    return `
      <div class="recent-message${evt.manual ? " recent-manual" : ""}">
        ${sourceHtml}
        <div class="recent-reply"><span class="recent-time">${timeStr(evt.timestampMs)}</span>${escapeHtml(evt.message)}</div>
      </div>`;
  }).join("");
}

/** Does `evt` match the current filter bar state? Pure - doesn't touch the DOM. */
function matchesFilters(evt) {
  const typeEnabled = {
    incoming: filterChat.checked,
    outgoing: filterBot.checked,
    ai_call: filterAi.checked,
    log: filterLog.checked,
  }[evt.type];
  if (!typeEnabled) return false;

  const whoQuery = filterWho.value.trim().toLowerCase();
  if (whoQuery) {
    const who = (evt.user || evt.botKey || "").toLowerCase();
    if (!who.includes(whoQuery)) return false;
  }

  const textQuery = filterText.value.trim().toLowerCase();
  if (textQuery) {
    const haystack = [evt.message, evt.prompt, evt.response, ...(evt.segments || []).map((s) => s.text)]
      .filter(Boolean).join(" ").toLowerCase();
    if (!haystack.includes(textQuery)) return false;
  }

  if (evt.type === "log" && filterLogLevel.value !== "all") {
    const hasLevel = (evt.segments || []).some((s) => s.level === filterLogLevel.value);
    if (!hasLevel) return false;
  }

  return true;
}

/** Builds the DOM node for one activity event. Pure - doesn't touch allEvents or the feed. */
function buildFeedEntryEl(evt) {
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
      `<span class="feed-tag tag-ai">${evt.mocked ? "FALLBACK AI" : "LIVE AI"}</span>` +
      `<span class="feed-bot">${escapeHtml(evt.botKey)}</span>` +
      `<span class="feed-ai-detail">prompt: ${escapeHtml(evt.prompt)}</span>` +
      `<span class="feed-ai-detail">reply: ${escapeHtml(evt.response)}</span>`;
  } else if (evt.type === "log") {
    const segments = evt.segments || [];
    const tagLevel = (segments[0] && segments[0].level) || "message";
    row.classList.add("feed-entry-log");

    // One log event can now cover several printed lines (one dispatched chat message's
    // whole diagnostic cascade) - split the flat segment list back into lines using each
    // segment's newLine marker, so multi-line entries render as actual separate lines.
    const lines = [];
    let currentLine = [];
    segments.forEach((seg) => {
      currentLine.push(seg);
      if (seg.newLine) {
        lines.push(currentLine);
        currentLine = [];
      }
    });
    if (currentLine.length) lines.push(currentLine);

    const lineHtml = (segs) => segs
      .map((seg) => `<span class="${LOG_TEXT_CLASS[seg.level] || "log-text-message"}">${escapeHtml(seg.text)}</span>`)
      .join("");

    const firstLineHtml = lines.length ? lineHtml(lines[0]) : "";
    const continuationHtml = lines.slice(1)
      .map((line) => `<div class="feed-log-continuation">${lineHtml(line)}</div>`)
      .join("");

    row.innerHTML = `<span class="feed-time">${timeStr(evt.timestampMs)}</span>` +
      `<span class="feed-tag ${LOG_TAG_CLASS[tagLevel] || "tag-log-message"}">LOG</span>` +
      `<span class="feed-log-text">${firstLineHtml}</span>` +
      continuationHtml;
  }

  return row;
}

/** Records an event into the retained history (independent of filters) and its side effects. */
function recordEvent(evt) {
  if (evt.type === "outgoing") recordOutgoing(evt);
  allEvents.push(evt);
  while (allEvents.length > ACTIVITY_HISTORY_LIMIT) allEvents.shift();
}

/** Fully re-renders the feed from the active source (live allEvents, or a loaded historical day) against the current filter bar state. */
function renderFeed() {
  feedEl.innerHTML = "";
  const source = viewingHistory ? historyEvents : allEvents;
  source.filter(matchesFilters).forEach((evt) => feedEl.appendChild(buildFeedEntryEl(evt)));
  feedEl.scrollTop = feedEl.scrollHeight;
  renderStats();
}

/** A freshly-arrived (WebSocket) event: always record it, but only render it live when not currently browsing history. */
function appendLiveEvent(evt) {
  recordEvent(evt);
  renderStats();
  if (viewingHistory) return;
  if (!matchesFilters(evt)) return;

  const atBottom = feedEl.scrollHeight - feedEl.scrollTop - feedEl.clientHeight < 40;
  feedEl.appendChild(buildFeedEntryEl(evt));
  if (atBottom) feedEl.scrollTop = feedEl.scrollHeight;
}

[filterChat, filterBot, filterAi, filterLog].forEach((el) => el.addEventListener("change", renderFeed));
[filterWho, filterText].forEach((el) => el.addEventListener("input", renderFeed));
filterLogLevel.addEventListener("change", renderFeed);

document.getElementById("clearFeed").addEventListener("click", () => {
  if (viewingHistory) return;
  allEvents.length = 0;
  feedEl.innerHTML = "";
  renderStats();
});

// ==================================================================================
// Observability stats: a client-side summary of the current (filtered) live session
// buffer - always computed from allEvents, independent of history-browsing mode,
// since it's meant to reflect this session's activity, not a past day's log.
// ==================================================================================

function computeStats(events) {
  const stats = {
    counts: { incoming: 0, outgoing: 0, ai_call: 0, log: 0 },
    outgoingByBot: new Map(),
    aiMockVsReal: { mock: 0, real: 0 },
    logByLevel: {},
    chatterCounts: new Map(),
    minTs: null,
    maxTs: null,
  };

  events.forEach((evt) => {
    stats.counts[evt.type] = (stats.counts[evt.type] || 0) + 1;
    if (stats.minTs === null || evt.timestampMs < stats.minTs) stats.minTs = evt.timestampMs;
    if (stats.maxTs === null || evt.timestampMs > stats.maxTs) stats.maxTs = evt.timestampMs;

    if (evt.type === "outgoing") {
      stats.outgoingByBot.set(evt.botKey, (stats.outgoingByBot.get(evt.botKey) || 0) + 1);
    } else if (evt.type === "ai_call") {
      if (evt.mocked) stats.aiMockVsReal.mock++;
      else stats.aiMockVsReal.real++;
    } else if (evt.type === "incoming") {
      stats.chatterCounts.set(evt.user, (stats.chatterCounts.get(evt.user) || 0) + 1);
    } else if (evt.type === "log") {
      (evt.segments || []).forEach((seg) => {
        stats.logByLevel[seg.level] = (stats.logByLevel[seg.level] || 0) + 1;
      });
    }
  });

  return stats;
}

function statRowHtml(label, value) {
  return `<div class="stats-row"><span class="stats-label">${escapeHtml(label)}</span><span class="stats-value">${value}</span></div>`;
}

function statsSectionHtml(title, rowsHtml) {
  return `<div class="stats-section"><div class="stats-section-title">${escapeHtml(title)}</div>${rowsHtml || `<div class="config-empty">none</div>`}</div>`;
}

function renderStats() {
  const statsBody = document.getElementById("statsBody");
  if (!statsBody) return;

  const events = allEvents.filter(matchesFilters);
  if (events.length === 0) {
    statsBody.innerHTML = `<div class="config-empty">no activity yet</div>`;
    return;
  }
  const stats = computeStats(events);

  const countsRows = ["incoming", "outgoing", "ai_call", "log"].map((t) => statRowHtml(t, stats.counts[t] || 0)).join("");

  const outgoingRows = [...stats.outgoingByBot.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([bot, count]) => statRowHtml(bot, count)).join("");

  const aiRows = (stats.aiMockVsReal.mock || stats.aiMockVsReal.real)
    ? statRowHtml("fallback", stats.aiMockVsReal.mock) + statRowHtml("live", stats.aiMockVsReal.real)
    : "";

  const logRows = Object.entries(stats.logByLevel)
    .sort((a, b) => b[1] - a[1])
    .map(([level, count]) => statRowHtml(level, count)).join("");

  const topChatters = [...stats.chatterCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([user, count]) => statRowHtml(user, count)).join("");

  const range = stats.minTs === stats.maxTs
    ? new Date(stats.minTs).toLocaleString()
    : `${new Date(stats.minTs).toLocaleString()} &rarr; ${new Date(stats.maxTs).toLocaleString()}`;

  statsBody.innerHTML = [
    statsSectionHtml("Events by type", countsRows),
    statsSectionHtml("Outgoing per bot", outgoingRows),
    statsSectionHtml("AI calls: fallback vs live", aiRows),
    statsSectionHtml("Log entries by level", logRows),
    statsSectionHtml("Top chatters (incoming)", topChatters),
    `<div class="stats-range">${range}</div>`,
  ].join("");
}

// ==================================================================================
// Activity history: browse a past day's log (read-only), loaded on demand.
// ==================================================================================

async function populateHistoryDates() {
  const dates = await api("/activity/history/dates");
  const previous = historyDateSelect.value;
  historyDateSelect.innerHTML = `<option value="">live</option>` +
    dates.map((d) => `<option value="${d}">${d}</option>`).join("");
  if (dates.includes(previous)) historyDateSelect.value = previous;
}

async function loadHistoryDate(date) {
  historyEvents = await api(`/activity/history/${encodeURIComponent(date)}`);
  viewingHistory = true;
  historyBannerDate.textContent = date;
  historyBanner.style.display = "flex";
  renderFeed();
}

function backToLiveFeed() {
  viewingHistory = false;
  historyEvents = [];
  historyDateSelect.value = "";
  historyBanner.style.display = "none";
  renderFeed();
}

historyDateSelect.addEventListener("change", () => {
  const date = historyDateSelect.value;
  if (!date) backToLiveFeed();
  else loadHistoryDate(date);
});
historyBackBtn.addEventListener("click", backToLiveFeed);

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

document.querySelector(".test-message-buttons").addEventListener("click", async (e) => {
  const btn = e.target.closest("button[data-channel]");
  if (!btn) return;
  const botKey = testMessageBotSelect.value;
  const messageInput = document.getElementById("testMessageInput");
  const message = messageInput.value.trim();
  if (!botKey || !message) return;
  const channel = btn.dataset.channel;
  await api("/test-message", { method: "POST", body: JSON.stringify({ botKey, message, channel }) });
  messageInput.value = "";
});

// ==================================================================================
// Quick-fire presets and random batch simulate
// ==================================================================================

const presetBotSelect = document.getElementById("presetBotSelect");
const testMessageBotSelect = document.getElementById("testMessageBotSelect");

/** Repopulates a bot-key <select> (preset picker, test-message picker, ...) from the current bot list, preserving the selection if it still exists. */
function populateBotSelect(select) {
  const previous = select.value;
  select.innerHTML = bots.map((b) => `<option value="${escapeHtml(b.key)}">${escapeHtml(b.key)}</option>`).join("");
  if (bots.some((b) => b.key === previous)) select.value = previous;
}

function fireSimulate(user, message) {
  return api("/simulate", { method: "POST", body: JSON.stringify({ user, message }) });
}

/** Whatever's currently typed in the simulate form's username field, or a generic fallback. */
function presetTestUser() {
  return document.getElementById("simulateUser").value.trim() || "tester";
}

document.querySelector(".simulate-presets").addEventListener("click", async (e) => {
  const btn = e.target.closest(".preset-btn");
  if (!btn) return;
  const bot = bots.find((b) => b.key === presetBotSelect.value);
  if (!bot) return;
  const ref = bot.triggerKeywords[0];
  const user = presetTestUser();

  switch (btn.dataset.preset) {
    case "hello":
      await fireSimulate(user, `hi ${ref}`);
      break;
    case "yes":
      await fireSimulate(user, `${ref} yes`);
      break;
    case "no":
      await fireSimulate(user, `${ref} no`);
      break;
    case "why":
      await fireSimulate(user, `${ref} why`);
      break;
    case "short":
      await fireSimulate(user, "hi");
      break;
    case "callout": {
      // botConfig().calloutWords is only this bot's own list - union with the shared common
      // ones too, since either alone is enough to trigger a callout.
      const [botConfig, commonConfig] = await Promise.all([
        api(`/bots/${encodeURIComponent(bot.key)}/config`),
        api("/common-config"),
      ]);
      const words = [...new Set([...botConfig.calloutWords, ...commonConfig.calloutWords])];
      if (words.length === 0) return;
      await fireSimulate(user, words[Math.floor(Math.random() * words.length)]);
      break;
    }
  }
});

const DEFAULT_BATCH_MESSAGES = [
  "just chatting here", "anyone else watching this", "lol nice one", "gg well played",
  "that was wild", "what's the game plan", "hey everyone", "wait what happened there",
  "haha true that", "this is a pretty good stream honestly",
];

function parsePool(text) {
  return text.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
}

function randomBatchUsername() {
  return `viewer${Math.floor(Math.random() * 9000) + 100}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

document.getElementById("batchFireBtn").addEventListener("click", async () => {
  const btn = document.getElementById("batchFireBtn");
  const count = Math.max(1, Math.min(200, Number(document.getElementById("batchCount").value) || 10));
  const userPool = parsePool(document.getElementById("batchUsers").value);
  const messagePool = parsePool(document.getElementById("batchMessages").value);

  btn.disabled = true;
  try {
    for (let i = 0; i < count; i++) {
      const user = userPool.length ? userPool[Math.floor(Math.random() * userPool.length)] : randomBatchUsername();
      const message = messagePool.length
        ? messagePool[Math.floor(Math.random() * messagePool.length)]
        : DEFAULT_BATCH_MESSAGES[Math.floor(Math.random() * DEFAULT_BATCH_MESSAGES.length)];
      await fireSimulate(user, message);
      await sleep(200);
    }
  } finally {
    btn.disabled = false;
  }
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
      appendLiveEvent(JSON.parse(msg.data));
    } catch (e) {
      console.error("bad activity event", e);
    }
  };
}

// ==================================================================================
// Bot cards: cooldown bars, send-as, recent messages
// ==================================================================================

/**
 * [displayName] is the bot's own transform word (e.g. "BLAMP") for the mainline row, the
 * type name for the other four. [pooledWith] (mainline row only) lists other bot keys
 * sharing this exact timer - a non-empty list renders a small badge so it's visually clear
 * this bar moving isn't a coincidence, it's the same underlying timer as those other bots'.
 */
function cooldownBarHtml(botKey, type, status, displayName, pooledWith = []) {
  const seconds = (status.remainingMs / 1000).toFixed(1);
  const progressPct = status.configuredMs > 0
    ? Math.round(((status.configuredMs - status.remainingMs) / status.configuredMs) * 100)
    : 100;
  const pct = status.ready ? 100 : Math.min(100, Math.max(0, progressPct));
  const isMainline = type === "mainline";
  const poolBadge = pooledWith.length
    ? `<span class="cooldown-pool-badge" title="Shared timer with ${escapeHtml(pooledWith.join(", "))}">&#128279; ${escapeHtml(pooledWith.join(", "))}</span>`
    : "";

  return `
    <div class="cooldown-row${isMainline ? " cooldown-row-mainline" : ""}" data-bot="${botKey}" data-type="${type}">
      <span class="cooldown-name">${escapeHtml(displayName)}</span>
      ${poolBadge}
      <div class="cooldown-bar-track">
        <div class="cooldown-bar-fill cooldown-bar-fill-${type} ${status.ready ? "ready" : ""}" style="width:${pct}%"></div>
      </div>
      <span class="cooldown-val">${status.ready ? "ready" : seconds + "s"}</span>
      <button class="cooldown-expire" title="Expire now">&#9199;</button>
    </div>`;
}

function renderBotCard(bot) {
  const card = document.createElement("div");
  // Collapsed by default - the cooldown bars just below the header (outside .card-body,
  // so they stay visible either way) are the front-and-center summary; everything else
  // (send-as, recent messages, full config) is a deliberate expand.
  card.className = "bot-card collapsed" + (bot.enabled ? "" : " bot-disabled");
  card.dataset.botKey = bot.key;
  card.innerHTML = `
    <div class="bot-card-header">
      <h3 class="collapsible-header" data-action="toggle-section"><span class="chevron">&#9660;</span> ${escapeHtml(bot.key)}</h3>
      <label class="switch bot-enable-switch" title="Enable/disable auto-responses">
        <input type="checkbox" class="bot-enable-toggle" ${bot.enabled ? "checked" : ""} />
        <span class="slider"></span>
      </label>
    </div>
    <div class="cooldowns"></div>
    <div class="card-body">
      <div class="bot-meta">${escapeHtml(bot.accountUsername)} &middot; mood: ${escapeHtml(bot.activeMood)} &middot; ${escapeHtml(bot.channel)}</div>
      <form class="send-form">
        <input type="text" placeholder="Send as ${escapeHtml(bot.key)}..." />
        <button type="submit">Send</button>
      </form>
      <div class="recent-header">
        Recent messages
        <button type="button" class="simulate-as-last-btn" data-bot="${escapeHtml(bot.key)}">simulate as last user</button>
      </div>
      <div class="recent-messages"><div class="recent-empty">no messages yet</div></div>
      <button class="config-toggle" data-bot="${escapeHtml(bot.key)}">&#9881; Configure</button>
      <div class="config-panel" style="display:none"></div>
    </div>
  `;

  card.querySelector(".bot-enable-toggle").addEventListener("change", async (e) => {
    await api(`/bots/${encodeURIComponent(bot.key)}/enabled`, { method: "POST", body: JSON.stringify({ enabled: e.target.checked }) });
    card.classList.toggle("bot-disabled", !e.target.checked);
  });

  card.querySelector(".send-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    // Scoped to the form itself, not card.querySelector("input") - the enable/disable
    // checkbox in .bot-card-header comes first in DOM order and would win instead.
    const input = e.target.querySelector("input");
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

  card.querySelector(".simulate-as-last-btn").addEventListener("click", () => {
    const list = recentByBot.get(bot.key) || [];
    const last = [...list].reverse().find((evt) => evt.sourceUser);
    if (!last) return;
    document.getElementById("simulateUser").value = last.sourceUser;
    const messageInput = document.getElementById("simulateMessage");
    messageInput.focus();
    const simulateCard = document.getElementById("simulateCard");
    simulateCard.classList.remove("collapsed");
    simulateCard.scrollIntoView({ behavior: "smooth", block: "nearest" });
  });

  return card;
}

// ==================================================================================
// Config panel
// ==================================================================================

const MATCH_TYPE_LABELS = { starts_with: "starts with", contains: "contains", ends_with: "ends with" };

function configGroupHtml(title, colorClass, innerHtml) {
  return `
    <div class="config-group ${colorClass}">
      <div class="config-group-title">${escapeHtml(title)}</div>
      <div class="config-group-body">${innerHtml}</div>
    </div>
  `;
}

/** A small "shared with X, Y" note for a config field that actually edits a MainlinePool shared across bots, not just this one. */
function pooledNoteHtml(pooledWith) {
  return pooledWith.length ? `<div class="config-pooled-note">&#128279; shared with ${escapeHtml(pooledWith.join(", "))}</div>` : "";
}

function configSectionHtml(botKey, config, cooldowns) {
  const identity = `
    ${chipListBlockHtml("bot", botKey, "Trigger keywords", "trigger-keywords", "keyword", config.triggerKeywords)}
    <div class="config-block">
      <h4>Active mood</h4>
      <select class="config-mood-select" data-bot="${escapeHtml(botKey)}">
        ${config.moods.map((m) => `<option value="${escapeHtml(m)}" ${m === config.activeMood ? "selected" : ""}>${escapeHtml(m)}</option>`).join("")}
      </select>
    </div>
  `;

  const greetings = helloTriggersBlockHtml("bot", botKey, config.helloTriggers);

  const moderation = `
    ${chipListBlockHtml("bot", botKey, "Excluded users", "excluded-users", "user", config.excludedUsers)}
    ${calloutWordsBlockHtml("bot", botKey, config.calloutWords, config.calloutResponseOverrides)}
    ${chipListBlockHtml("bot", botKey, "Forbidden words", "forbidden-words", "word", config.forbiddenWords)}
  `;

  const personality = `
    ${transformWordsBlockHtml(botKey, config.transformWords)}
    ${editableTextBlockHtml(botKey, "Self-quote marker", "/self-quote-marker", config.selfQuoteMarker)}
    ${editableTextBlockHtml(botKey, "Prompt: voice", "/prompts/voice", config.prompts.voice, true)}
    ${editableTextBlockHtml(botKey, "Prompt: transform", "/prompts/transform", config.prompts.transform, true)}
    ${editableTextBlockHtml(botKey, "Prompt: wisdom", "/prompts/wisdom", config.prompts.wisdom, true)}
    ${editableTextBlockHtml(botKey, "Prompt: request", "/prompts/request", config.prompts.request, true)}
  `;

  const timing = `
    <div class="config-block">
      <h4>Message length</h4>
      <div class="config-numfields">
        <label>min <input type="number" class="cfg-num" data-field="msg-min" data-autosave="message-length" data-bot="${escapeHtml(botKey)}" data-saved="${config.messageLengthMinimum}" value="${config.messageLengthMinimum}" /></label>
        <label>max <input type="number" class="cfg-num" data-field="msg-max" data-autosave="message-length" data-bot="${escapeHtml(botKey)}" data-saved="${config.messageLengthMaximum}" value="${config.messageLengthMaximum}" /></label>
      </div>
    </div>

    <div class="config-block">
      <h4>Similarity threshold</h4>
      <div class="config-numfields">
        <input type="number" class="cfg-num" data-autosave="similarity" data-bot="${escapeHtml(botKey)}" data-saved="${config.similarityThreshold}" value="${config.similarityThreshold}" />
      </div>
    </div>

    <div class="config-block">
      <h4>Minimum messages between mainline actions</h4>
      ${pooledNoteHtml(cooldowns.mainlinePooledWith)}
      <div class="config-numfields">
        <input type="number" class="cfg-num" data-autosave="min-messages" data-bot="${escapeHtml(botKey)}" data-saved="${config.minimumMessagesBetween}" value="${config.minimumMessagesBetween}" />
      </div>
    </div>
  `;

  const cooldownsHtml = `
    <div class="config-block">
      <h4>Cooldown durations (ms)</h4>
      <div class="config-numfields cooldown-durations">
        <label>wisdom <input type="number" class="cfg-num" data-autosave="cooldown-duration" data-cooldown-type="wisdom" data-bot="${escapeHtml(botKey)}" data-saved="${cooldowns.wisdom.configuredMs}" value="${cooldowns.wisdom.configuredMs}" /></label>
        <label>callout <input type="number" class="cfg-num" data-autosave="cooldown-duration" data-cooldown-type="callout" data-bot="${escapeHtml(botKey)}" data-saved="${cooldowns.callout.configuredMs}" value="${cooldowns.callout.configuredMs}" /></label>
        <label>retry <input type="number" class="cfg-num" data-autosave="cooldown-duration" data-cooldown-type="retry" data-bot="${escapeHtml(botKey)}" data-saved="${cooldowns.retry.configuredMs}" value="${cooldowns.retry.configuredMs}" /></label>
        <label>request <input type="number" class="cfg-num" data-autosave="cooldown-duration" data-cooldown-type="request" data-bot="${escapeHtml(botKey)}" data-saved="${cooldowns.request.configuredMs}" value="${cooldowns.request.configuredMs}" /></label>
      </div>
    </div>

    <div class="config-block">
      <h4>Mainline interval bounds (ms)</h4>
      ${pooledNoteHtml(cooldowns.mainlinePooledWith)}
      <div class="config-numfields">
        <label>min <input type="number" class="cfg-num" data-field="mainline-min" data-autosave="mainline-interval" data-bot="${escapeHtml(botKey)}" data-saved="${cooldowns.mainlineIntervalMinimumMs}" value="${cooldowns.mainlineIntervalMinimumMs}" /></label>
        <label>max <input type="number" class="cfg-num" data-field="mainline-max" data-autosave="mainline-interval" data-bot="${escapeHtml(botKey)}" data-saved="${cooldowns.mainlineIntervalMaximumMs}" value="${cooldowns.mainlineIntervalMaximumMs}" /></label>
      </div>
    </div>
  `;

  const featuresGroup = config.features.length
    ? configGroupHtml("Features (read-only)", "config-group-features", `
        <ul class="config-readonly-list">${config.features.map((f) => `<li>${escapeHtml(f)}</li>`).join("")}</ul>
      `)
    : "";

  return `
    ${configGroupHtml("Identity & triggers", "config-group-identity", identity)}
    ${configGroupHtml("Greetings", "config-group-greetings", greetings)}
    ${configGroupHtml("Moderation", "config-group-moderation", moderation)}
    ${configGroupHtml("Personality & prompts", "config-group-personality", personality)}
    ${configGroupHtml("Eligibility & timing", "config-group-timing", timing)}
    ${configGroupHtml("Cooldowns", "config-group-cooldowns", cooldownsHtml)}
    ${featuresGroup}
  `;
}

/** `scope` is "bot" (needs `botKey`) or "common" (shared across every bot, `botKey` unused). */
function scopedPrefix(scope, botKey) {
  return scope === "common" ? "/common-config" : `/bots/${encodeURIComponent(botKey)}`;
}

async function refreshScoped(scope, botKey) {
  if (scope === "common") await refreshCommonConfigPanel();
  else await refreshConfigPanel(botKey);
}

/** Wraps a list-shaped config block in its own collapsible header, independent of the outer config-group's collapse state. */
function collapsibleBlockHtml(title, innerHtml) {
  return `
    <div class="config-block collapsible-config-block">
      <h4 class="collapsible-header" data-action="toggle-section"><span class="chevron">&#9660;</span> ${escapeHtml(title)}</h4>
      <div class="config-block-body">${innerHtml}</div>
    </div>
  `;
}

/** Chips are clickable (opens the edit modal, per-item Save/Delete) rather than carrying their own remove button. */
function chipListBlockHtml(scope, botKey, title, listKey, fieldName, items) {
  const chips = items.map((item) => `
    <span class="chip" style="--chip-hue:${hashHue(item)}" data-action="edit-chip" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-list="${listKey}" data-field="${fieldName}" data-value="${escapeHtml(item)}">${escapeHtml(item)}</span>
  `).join("") || `<span class="config-empty">none</span>`;

  return collapsibleBlockHtml(title, `
    <div class="chip-list">${chips}</div>
    <div class="config-add-form">
      <input type="text" class="chip-input" placeholder="add..." />
      <button data-action="add-chip" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-list="${listKey}" data-field="${fieldName}">Add</button>
    </div>
  `);
}

/** Each chip shows just the word ("turkey"), or "word -> response" when that word has a response override. */
function calloutWordsBlockHtml(scope, botKey, words, overrides) {
  const chips = words.map((word) => {
    const response = overrides[word] || "";
    const label = response ? `${escapeHtml(word)} &rarr; ${escapeHtml(response)}` : escapeHtml(word);
    return `
      <span class="chip" style="--chip-hue:${hashHue(word)}" data-action="edit-callout-word" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-word="${escapeHtml(word)}" data-response="${escapeHtml(response)}">${label}</span>
    `;
  }).join("") || `<span class="config-empty">none</span>`;

  return collapsibleBlockHtml("Callout words", `
    <div class="chip-list">${chips}</div>
    <div class="config-add-form">
      <input type="text" class="callout-word-input" placeholder="word" />
      <input type="text" class="callout-response-input" placeholder="response override (optional)" />
      <button data-action="add-callout-word" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}">Add</button>
    </div>
  `);
}

/** Transform words are always per-bot (never common) - relative weights drive a weighted-random pick, sliders let you balance them, with a live-computed percentage. */
function transformWordsBlockHtml(botKey, words) {
  const totalWeight = words.reduce((sum, w) => sum + w.weight, 0) || 1;
  const rows = words.map((w) => {
    const pct = Math.round((w.weight / totalWeight) * 100);
    return `
      <div class="weighted-word-row">
        <span class="weighted-word-text">${escapeHtml(w.word)}</span>
        <input type="range" class="weighted-word-slider" min="1" max="100"
               data-autosave="transform-word-weight" data-bot="${escapeHtml(botKey)}" data-word="${escapeHtml(w.word)}"
               data-saved="${w.weight}" value="${w.weight}" />
        <span class="weighted-word-pct">${pct}%</span>
        <button data-action="remove-transform-word" data-bot="${escapeHtml(botKey)}" data-word="${escapeHtml(w.word)}" title="Remove">&times;</button>
      </div>
    `;
  }).join("") || `<div class="config-empty">none</div>`;

  return collapsibleBlockHtml("Transform words", `
    <div class="weighted-words-list">${rows}</div>
    <div class="config-add-form">
      <input type="text" class="transform-word-input" placeholder="add word..." />
      <button data-action="add-transform-word" data-bot="${escapeHtml(botKey)}">Add</button>
    </div>
  `);
}

function commonConfigSectionHtml(config) {
  const greetings = helloTriggersBlockHtml("common", null, config.helloTriggers);

  const moderation = `
    ${chipListBlockHtml("common", null, "Excluded users", "excluded-users", "user", config.excludedUsers)}
    ${calloutWordsBlockHtml("common", null, config.calloutWords, config.calloutResponseOverrides)}
    ${chipListBlockHtml("common", null, "Forbidden words", "forbidden-words", "word", config.forbiddenWords)}
  `;

  return `
    ${configGroupHtml("Greetings", "config-group-greetings", greetings)}
    ${configGroupHtml("Moderation", "config-group-moderation", moderation)}
  `;
}

async function refreshCommonConfigPanel() {
  const container = document.getElementById("commonConfigBody");
  if (!container) return;
  const config = await api("/common-config");
  container.innerHTML = commonConfigSectionHtml(config);
  await refreshRecentTargets();
}

async function refreshRecentTargets() {
  const limitInput = document.getElementById("recentTargetsLimit");
  const listEl = document.getElementById("recentTargetsList");
  if (!limitInput || !listEl) return;

  const { limit, recent } = await api("/recent-targets");
  limitInput.value = limit;
  limitInput.dataset.saved = limit;
  listEl.innerHTML = recent.length
    ? recent.map((user) => `<span class="chip" style="--chip-hue:${hashHue(user)}">${escapeHtml(user)}</span>`).join("")
    : `<span class="config-empty">none right now</span>`;
}

document.getElementById("recentTargetsLimit").addEventListener("focusout", async (e) => {
  const el = e.target;
  await autosaveSingle(el, "/recent-targets/limit", (limit) => ({ limit }));
  await refreshRecentTargets();
});

/** A single editable string (self-quote marker, or one of the four prompt fields) - click to open the edit modal. */
function editableTextBlockHtml(botKey, title, endpoint, value, isLong) {
  return `
    <div class="config-block">
      <h4>${title}</h4>
      <div class="editable-text${isLong ? " editable-text-long" : ""}" data-action="edit-text" data-bot="${escapeHtml(botKey)}"
           data-endpoint="${endpoint}" data-title="${escapeHtml(title)}" data-long="${isLong ? "1" : "0"}" data-value="${escapeHtml(value || "")}">
        ${value ? escapeHtml(value) : '<span class="config-empty">(click to set)</span>'}
      </div>
    </div>
  `;
}

function helloTriggerRowHtml(scope, botKey, trigger, index) {
  return `
    <div class="hello-trigger-row" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-index="${index}">
      <select class="hello-match-type" data-autosave="hello-trigger" data-saved="${trigger.matchType}">
        ${Object.entries(MATCH_TYPE_LABELS).map(([v, label]) => `<option value="${v}" ${trigger.matchType === v ? "selected" : ""}>${label}</option>`).join("")}
      </select>
      <input type="text" class="hello-value" data-autosave="hello-trigger" data-saved="${escapeHtml(trigger.value)}" value="${escapeHtml(trigger.value)}" placeholder="text - use {ref} for the bot's name" />
      <input type="text" class="hello-also" data-autosave="hello-trigger" data-saved="${escapeHtml(trigger.alsoContains || "")}" value="${escapeHtml(trigger.alsoContains || "")}" placeholder="also contains (optional)" />
      <button data-action="remove-hello-trigger" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-index="${index}" title="Remove">&times;</button>
    </div>
  `;
}

function helloTriggersBlockHtml(scope, botKey, triggers) {
  const rows = triggers.map((t, i) => helloTriggerRowHtml(scope, botKey, t, i)).join("") || `<div class="config-empty">none</div>`;
  return collapsibleBlockHtml("Hello triggers", `
    <div class="hello-triggers-list">${rows}</div>
    <div class="hello-trigger-row hello-trigger-new">
      <select class="hello-match-type-new">
        ${Object.entries(MATCH_TYPE_LABELS).map(([v, label]) => `<option value="${v}">${label}</option>`).join("")}
      </select>
      <input type="text" class="hello-value-new" placeholder="text - use {ref} for the bot's name" />
      <input type="text" class="hello-also-new" placeholder="also contains (optional)" />
      <button data-action="add-hello-trigger" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}">Add</button>
    </div>
  `);
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

botsPanelEl.addEventListener("click", async (e) => {
  const target = e.target.closest("[data-action]");
  if (!target) return;
  const botKey = target.dataset.bot;
  const block = target.closest(".config-block");

  switch (target.dataset.action) {
    case "toggle-section": {
      // Nested list blocks (excluded users, hello triggers, etc) collapse independently of
      // the outer bot card / top-level overrides card.
      const section = block && block.classList.contains("collapsible-config-block") ? block : target.closest(".bot-card, .overrides-card");
      if (section) section.classList.toggle("collapsed");
      break;
    }
    case "edit-chip": {
      const { scope, list, field, value } = target.dataset;
      const prefix = scopedPrefix(scope, botKey);
      openEditModal({
        title: `Edit ${field}`,
        fields: [{ id: "value", value }],
        onSave: async (values) => {
          const newValue = values.value.trim();
          if (!newValue || newValue.toLowerCase() === value.toLowerCase()) return;
          await api(`${prefix}/${list}`, { method: "POST", body: JSON.stringify({ [field]: newValue }) });
          await api(`${prefix}/${list}/${encodeURIComponent(value)}`, { method: "DELETE" });
          await refreshScoped(scope, botKey);
        },
        onDelete: async () => {
          await api(`${prefix}/${list}/${encodeURIComponent(value)}`, { method: "DELETE" });
          await refreshScoped(scope, botKey);
        },
      });
      break;
    }
    case "add-chip": {
      const { scope, list, field } = target.dataset;
      const input = block.querySelector(".chip-input");
      const value = input.value.trim();
      if (!value) return;
      await api(`${scopedPrefix(scope, botKey)}/${list}`, {
        method: "POST",
        body: JSON.stringify({ [field]: value }),
      });
      await refreshScoped(scope, botKey);
      break;
    }
    case "edit-callout-word": {
      const { scope, word, response } = target.dataset;
      const prefix = scopedPrefix(scope, botKey);
      openEditModal({
        title: "Edit callout word",
        fields: [
          { id: "word", label: "Word", value: word },
          { id: "response", label: "Response override (optional)", value: response },
        ],
        onSave: async (values) => {
          const newWord = values.word.trim().toLowerCase();
          const newResponse = values.response.trim();
          if (!newWord) return;
          if (newWord !== word.toLowerCase()) {
            await api(`${prefix}/callout-words`, { method: "POST", body: JSON.stringify({ word: newWord }) });
            await api(`${prefix}/callout-words/${encodeURIComponent(word)}`, { method: "DELETE" });
            await api(`${prefix}/callout-overrides/${encodeURIComponent(word)}`, { method: "DELETE" });
          }
          if (newResponse) {
            await api(`${prefix}/callout-overrides`, { method: "POST", body: JSON.stringify({ word: newWord, response: newResponse }) });
          } else {
            await api(`${prefix}/callout-overrides/${encodeURIComponent(newWord)}`, { method: "DELETE" });
          }
          await refreshScoped(scope, botKey);
        },
        onDelete: async () => {
          await api(`${prefix}/callout-words/${encodeURIComponent(word)}`, { method: "DELETE" });
          await api(`${prefix}/callout-overrides/${encodeURIComponent(word)}`, { method: "DELETE" });
          await refreshScoped(scope, botKey);
        },
      });
      break;
    }
    case "add-callout-word": {
      const { scope } = target.dataset;
      const word = block.querySelector(".callout-word-input").value.trim();
      const response = block.querySelector(".callout-response-input").value.trim();
      if (!word) return;
      await api(`${scopedPrefix(scope, botKey)}/callout-words`, { method: "POST", body: JSON.stringify({ word }) });
      if (response) {
        await api(`${scopedPrefix(scope, botKey)}/callout-overrides`, { method: "POST", body: JSON.stringify({ word, response }) });
      }
      await refreshScoped(scope, botKey);
      break;
    }
    case "edit-text": {
      const { endpoint, value, title, long } = target.dataset;
      openEditModal({
        title,
        fields: [{ id: "value", value, type: long === "1" ? "textarea" : "text" }],
        onSave: async (values) => {
          await api(`/bots/${encodeURIComponent(botKey)}${endpoint}`, { method: "POST", body: JSON.stringify({ value: values.value }) });
          await refreshConfigPanel(botKey);
        },
      });
      break;
    }
    case "remove-hello-trigger": {
      const { scope } = target.dataset;
      await api(`${scopedPrefix(scope, botKey)}/hello-triggers/${target.dataset.index}`, { method: "DELETE" });
      await refreshScoped(scope, botKey);
      break;
    }
    case "add-hello-trigger": {
      const { scope } = target.dataset;
      const matchType = block.querySelector(".hello-match-type-new").value;
      const value = block.querySelector(".hello-value-new").value.trim();
      const alsoContains = block.querySelector(".hello-also-new").value.trim();
      if (!value) return;
      await api(`${scopedPrefix(scope, botKey)}/hello-triggers`, {
        method: "POST",
        body: JSON.stringify({ matchType, value, alsoContains: alsoContains || null }),
      });
      await refreshScoped(scope, botKey);
      break;
    }
    case "remove-transform-word": {
      await api(`/bots/${encodeURIComponent(botKey)}/transform-words/${encodeURIComponent(target.dataset.word)}`, { method: "DELETE" });
      await refreshConfigPanel(botKey);
      break;
    }
    case "add-transform-word": {
      const input = block.querySelector(".transform-word-input");
      const word = input.value.trim();
      if (!word) return;
      await api(`/bots/${encodeURIComponent(botKey)}/transform-words`, { method: "POST", body: JSON.stringify({ word }) });
      await refreshConfigPanel(botKey);
      break;
    }
  }
});

// ==================================================================================
// Autosave: numeric fields and hello-trigger rows save on blur instead of a Save
// button, but only when the value actually changed from what's already saved
// (tracked via each field's data-saved attribute) and parses as a number.
// ==================================================================================

async function autosave(path, body) {
  try {
    await api(path, { method: "POST", body: JSON.stringify(body) });
    return true;
  } catch (e) {
    console.error("autosave failed", path, e);
    return false;
  }
}

/** Brief border flash so a blur-triggered save (no button to click) still gives feedback. */
function flashSaved(...elements) {
  elements.forEach((el) => {
    el.classList.add("autosave-flash");
    setTimeout(() => el.classList.remove("autosave-flash"), 600);
  });
}

function numChanged(el) {
  return el.value.trim() !== "" && el.value !== el.dataset.saved && Number.isFinite(Number(el.value));
}

async function autosaveSingle(el, path, body) {
  if (!numChanged(el)) return;
  if (await autosave(path, body(Number(el.value)))) {
    el.dataset.saved = el.value;
    flashSaved(el);
  }
}

async function autosavePair(container, fieldA, fieldB, path, body) {
  const elA = container.querySelector(`[data-field="${fieldA}"]`);
  const elB = container.querySelector(`[data-field="${fieldB}"]`);
  const aChanged = elA.value !== elA.dataset.saved;
  const bChanged = elB.value !== elB.dataset.saved;
  if (!aChanged && !bChanged) return;
  if (elA.value.trim() === "" || elB.value.trim() === "") return;
  if (!Number.isFinite(Number(elA.value)) || !Number.isFinite(Number(elB.value))) return;
  if (await autosave(path, body(Number(elA.value), Number(elB.value)))) {
    elA.dataset.saved = elA.value;
    elB.dataset.saved = elB.value;
    flashSaved(elA, elB);
  }
}

async function autosaveHelloTrigger(el) {
  const row = el.closest(".hello-trigger-row");
  const matchTypeEl = row.querySelector(".hello-match-type");
  const valueEl = row.querySelector(".hello-value");
  const alsoEl = row.querySelector(".hello-also");
  if (matchTypeEl.value === matchTypeEl.dataset.saved && valueEl.value === valueEl.dataset.saved && alsoEl.value === alsoEl.dataset.saved) return;
  const value = valueEl.value.trim();
  if (!value) return;

  const { scope, bot: botKey, index } = row.dataset;
  const alsoContains = alsoEl.value.trim();
  const ok = await autosave(`${scopedPrefix(scope, botKey)}/hello-triggers/${index}`, {
    matchType: matchTypeEl.value,
    value,
    alsoContains: alsoContains || null,
  });
  if (ok) {
    matchTypeEl.dataset.saved = matchTypeEl.value;
    valueEl.dataset.saved = valueEl.value;
    alsoEl.dataset.saved = alsoEl.value;
    flashSaved(matchTypeEl, valueEl, alsoEl);
  }
}

botsPanelEl.addEventListener("focusout", async (e) => {
  const el = e.target;
  const kind = el.dataset.autosave;
  if (!kind) return;
  const botKey = el.dataset.bot;
  const block = el.closest(".config-block");

  switch (kind) {
    case "message-length":
      await autosavePair(block, "msg-min", "msg-max", `/bots/${encodeURIComponent(botKey)}/message-length`,
        (minimum, maximum) => ({ minimum, maximum }));
      break;
    case "similarity":
      await autosaveSingle(el, `/bots/${encodeURIComponent(botKey)}/similarity-threshold`, (threshold) => ({ threshold }));
      break;
    case "min-messages":
      await autosaveSingle(el, `/bots/${encodeURIComponent(botKey)}/minimum-messages-between`, (count) => ({ count }));
      break;
    case "cooldown-duration":
      await autosaveSingle(el, `/bots/${encodeURIComponent(botKey)}/cooldown-duration/${el.dataset.cooldownType}`, (ms) => ({ ms }));
      break;
    case "mainline-interval":
      await autosavePair(block, "mainline-min", "mainline-max", `/bots/${encodeURIComponent(botKey)}/mainline-interval`,
        (minMs, maxMs) => ({ minMs, maxMs }));
      break;
    case "hello-trigger":
      await autosaveHelloTrigger(el);
      break;
    case "transform-word-weight":
      await autosaveSingle(el, `/bots/${encodeURIComponent(botKey)}/transform-words/${encodeURIComponent(el.dataset.word)}/weight`, (weight) => ({ weight }));
      break;
  }
});

/** Live percentage preview across a weighted-words block, recomputed as any slider moves (before the autosave-on-blur actually persists anything). */
botsPanelEl.addEventListener("input", (e) => {
  if (!e.target.classList.contains("weighted-word-slider")) return;
  const list = e.target.closest(".weighted-words-list");
  if (!list) return;

  const rows = [...list.querySelectorAll(".weighted-word-row")];
  const weights = rows.map((row) => Number(row.querySelector(".weighted-word-slider").value) || 0);
  const total = weights.reduce((a, b) => a + b, 0) || 1;
  rows.forEach((row, i) => {
    row.querySelector(".weighted-word-pct").textContent = `${Math.round((weights[i] / total) * 100)}%`;
  });
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
      container.innerHTML = COOLDOWN_TYPES
        .map((t) => cooldownBarHtml(bot.key, t, snapshot[t], t === "mainline" ? snapshot.label : t, t === "mainline" ? snapshot.mainlinePooledWith : []))
        .join("");
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

document.getElementById("expireAllBtn").addEventListener("click", async () => {
  await api("/cooldowns/expire-all", { method: "POST" });
  await refreshCooldowns();
});

document.getElementById("fastForwardBtn").addEventListener("click", async () => {
  const minutes = Number(document.getElementById("fastForwardMinutes").value);
  if (!Number.isFinite(minutes) || minutes <= 0) return;
  await api("/cooldowns/fast-forward", { method: "POST", body: JSON.stringify({ ms: Math.round(minutes * 60000) }) });
  await refreshCooldowns();
});

async function loadBots() {
  bots = await api("/bots");
  botCardsEl.innerHTML = "";
  bots.forEach((bot) => botCardsEl.appendChild(renderBotCard(bot)));
  bots.forEach((bot) => renderRecentMessages(bot.key));
  populateBotSelect(presetBotSelect);
  populateBotSelect(testMessageBotSelect);
  await refreshCooldowns();
}

async function loadInitialFeed() {
  const events = await api("/activity?limit=200");
  events.forEach(recordEvent);
  renderFeed();
}

const fallbackBanner = document.getElementById("fallbackBanner");

async function loadMockMode() {
  const { enabled, automatic, reason, sinceMs } = await api("/mock");
  mockSwitch.checked = enabled;
  mockLabel.classList.toggle("auto", !!automatic);
  mockLabel.classList.toggle("live", !enabled && !automatic);

  if (automatic) {
    mockLabel.textContent = "FALLBACK - AUTOMATIC";
    fallbackBanner.className = "fallback-banner";
    fallbackBanner.innerHTML =
      `<strong>Fallback mode was switched on automatically:</strong> ${escapeHtml(reason)}. ` +
      `Bots are sending canned replies. The model is rechecked every minute and live replies resume on their own ` +
      `<span class="hint">(since ${new Date(sinceMs).toLocaleTimeString()})</span> <button type="button" id="retryModelBtn">Retry now</button>`;
    fallbackBanner.style.display = "block";
  } else if (enabled) {
    mockLabel.textContent = "FALLBACK MODE (canned replies)";
    fallbackBanner.className = "fallback-banner manual";
    fallbackBanner.innerHTML = `<strong>Fallback mode is ON.</strong> Bots are sending canned replies instead of calling the model. Flip the switch off to go live.`;
    fallbackBanner.style.display = "block";
  } else {
    mockLabel.textContent = "LIVE (LOCAL MODEL)";
    fallbackBanner.style.display = "none";
  }
}

// An automatic fallback can start or end at any time, so keep the banner honest without a page reload.
setInterval(() => loadMockMode().catch(() => {}), 5000);

// "Retry now" = turn manual mode off, which also drops an automatic fallback.
fallbackBanner.addEventListener("click", async (e) => {
  if (e.target.id !== "retryModelBtn") return;
  await api("/mock", { method: "POST", body: JSON.stringify({ enabled: false }) });
  await loadMockMode();
});

mockSwitch.addEventListener("change", async () => {
  await api("/mock", { method: "POST", body: JSON.stringify({ enabled: mockSwitch.checked }) });
  await loadMockMode();
});

async function loadModels() {
  const { available, selected } = await api("/models");
  if (!available.length) {
    modelSelect.style.display = "none";
    return;
  }
  modelSelect.innerHTML = available.map((key) => `<option value="${escapeHtml(key)}">${escapeHtml(key)}</option>`).join("");
  modelSelect.value = selected;
}

modelSelect.addEventListener("change", async () => {
  await api("/model", { method: "POST", body: JSON.stringify({ key: modelSelect.value }) });
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
  await Promise.all([loadBots(), loadInitialFeed(), loadMockMode(), loadModels(), loadOverrides(), refreshCommonConfigPanel(), populateHistoryDates()]);
  // loadBots and loadInitialFeed race independently, so re-sync recent-message cards once
  // both have definitely finished, in case events arrived before their bot's card existed.
  bots.forEach((bot) => renderRecentMessages(bot.key));
  connectWebSocket();
  setInterval(refreshCooldowns, 1000);
}

init();

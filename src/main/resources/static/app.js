const feedEl = document.getElementById("feed");
const botCardsEl = document.getElementById("botCards");
const botsPanelEl = document.getElementById("botsPanel");
const mockSwitch = document.getElementById("mockSwitch");
const mockLabel = document.getElementById("mockLabel");
const connStatus = document.getElementById("connStatus");
const filterChat = document.getElementById("filterChat");
const filterBot = document.getElementById("filterBot");
const filterAi = document.getElementById("filterAi");
const filterLog = document.getElementById("filterLog");
const filterWho = document.getElementById("filterWho");
const filterText = document.getElementById("filterText");
const filterLogLevel = document.getElementById("filterLogLevel");
const overridesListEl = document.getElementById("overridesList");

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
  container.innerHTML = list.slice().reverse().map((evt) => `
    <div class="recent-message${evt.manual ? " recent-manual" : ""}">
      <span class="recent-time">${timeStr(evt.timestampMs)}</span>${escapeHtml(evt.message)}
    </div>`).join("");
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
      `<span class="feed-tag tag-ai">${evt.mocked ? "MOCK AI" : "REAL AI"}</span>` +
      `<span class="feed-bot">${escapeHtml(evt.botKey)}</span>` +
      `<span class="feed-ai-detail">prompt: ${escapeHtml(evt.prompt.slice(0, 200))}</span>` +
      `<span class="feed-ai-detail">reply: ${escapeHtml(evt.response)}</span>`;
  } else if (evt.type === "log") {
    const segments = evt.segments || [];
    const tagLevel = (segments[0] && segments[0].level) || "message";
    row.classList.add("feed-entry-log");
    const segmentsHtml = segments
      .map((seg) => `<span class="${LOG_TEXT_CLASS[seg.level] || "log-text-message"}">${escapeHtml(seg.text)}</span>`)
      .join("");
    row.innerHTML = `<span class="feed-time">${timeStr(evt.timestampMs)}</span>` +
      `<span class="feed-tag ${LOG_TAG_CLASS[tagLevel] || "tag-log-message"}">LOG</span>` +
      `<span class="feed-log-text">${segmentsHtml}</span>`;
  }

  return row;
}

/** Records an event into the retained history (independent of filters) and its side effects. */
function recordEvent(evt) {
  if (evt.type === "outgoing") recordOutgoing(evt);
  allEvents.push(evt);
  while (allEvents.length > ACTIVITY_HISTORY_LIMIT) allEvents.shift();
}

/** Fully re-renders the feed from allEvents against the current filter bar state. */
function renderFeed() {
  feedEl.innerHTML = "";
  allEvents.filter(matchesFilters).forEach((evt) => feedEl.appendChild(buildFeedEntryEl(evt)));
  feedEl.scrollTop = feedEl.scrollHeight;
}

/** A freshly-arrived (WebSocket) event: record it, and append it live if it currently matches. */
function appendLiveEvent(evt) {
  recordEvent(evt);
  if (!matchesFilters(evt)) return;

  const atBottom = feedEl.scrollHeight - feedEl.scrollTop - feedEl.clientHeight < 40;
  feedEl.appendChild(buildFeedEntryEl(evt));
  if (atBottom) feedEl.scrollTop = feedEl.scrollHeight;
}

[filterChat, filterBot, filterAi, filterLog].forEach((el) => el.addEventListener("change", renderFeed));
[filterWho, filterText].forEach((el) => el.addEventListener("input", renderFeed));
filterLogLevel.addEventListener("change", renderFeed);

document.getElementById("clearFeed").addEventListener("click", () => {
  allEvents.length = 0;
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
      appendLiveEvent(JSON.parse(msg.data));
    } catch (e) {
      console.error("bad activity event", e);
    }
  };
}

// ==================================================================================
// Bot cards: cooldown bars, send-as, recent messages
// ==================================================================================

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
    <h3 class="collapsible-header" data-action="toggle-section"><span class="chevron">&#9660;</span> ${escapeHtml(bot.key)}</h3>
    <div class="card-body">
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
    </div>
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

// ==================================================================================
// Config panel
// ==================================================================================

const MATCH_TYPE_LABELS = { starts_with: "starts with", contains: "contains", ends_with: "ends with" };

function configSectionHtml(botKey, config, cooldowns) {
  return `
    ${chipListBlockHtml("bot", botKey, "Trigger keywords", "trigger-keywords", "keyword", config.triggerKeywords)}

    <div class="config-block">
      <h4>Active mood</h4>
      <select class="config-mood-select" data-bot="${escapeHtml(botKey)}">
        ${config.moods.map((m) => `<option value="${escapeHtml(m)}" ${m === config.activeMood ? "selected" : ""}>${escapeHtml(m)}</option>`).join("")}
      </select>
    </div>

    ${helloTriggersBlockHtml(botKey, config.helloTriggers)}

    ${chipListBlockHtml("bot", botKey, "Excluded users", "excluded-users", "user", config.excludedUsers)}
    ${chipListBlockHtml("bot", botKey, "Callout words", "callout-words", "word", config.calloutWords)}
    ${chipListBlockHtml("bot", botKey, "Forbidden words", "forbidden-words", "word", config.forbiddenWords)}
    ${overrideListBlockHtml("bot", botKey, config.calloutResponseOverrides)}
    ${chipListBlockHtml("bot", botKey, "Transform words", "transform-words", "word", config.transformWords)}

    ${editableTextBlockHtml(botKey, "Self-quote marker", "/self-quote-marker", config.selfQuoteMarker)}
    ${editableTextBlockHtml(botKey, "Prompt: voice", "/prompts/voice", config.prompts.voice, true)}
    ${editableTextBlockHtml(botKey, "Prompt: transform", "/prompts/transform", config.prompts.transform, true)}
    ${editableTextBlockHtml(botKey, "Prompt: wisdom", "/prompts/wisdom", config.prompts.wisdom, true)}
    ${editableTextBlockHtml(botKey, "Prompt: request", "/prompts/request", config.prompts.request, true)}

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

    ${config.features.length ? `
    <div class="config-block">
      <h4>Features (read-only)</h4>
      <ul class="config-readonly-list">${config.features.map((f) => `<li>${escapeHtml(f)}</li>`).join("")}</ul>
    </div>` : ""}
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

/** Chips are clickable (opens the edit modal, per-item Save/Delete) rather than carrying their own remove button. */
function chipListBlockHtml(scope, botKey, title, listKey, fieldName, items) {
  const chips = items.map((item) => `
    <span class="chip" data-action="edit-chip" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-list="${listKey}" data-field="${fieldName}" data-value="${escapeHtml(item)}">${escapeHtml(item)}</span>
  `).join("") || `<span class="config-empty">none</span>`;

  return `
    <div class="config-block">
      <h4>${title}</h4>
      <div class="chip-list">${chips}</div>
      <div class="config-add-form">
        <input type="text" class="chip-input" placeholder="add..." />
        <button data-action="add-chip" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-list="${listKey}" data-field="${fieldName}">Add</button>
      </div>
    </div>
  `;
}

function overrideListBlockHtml(scope, botKey, overrides) {
  const rows = Object.entries(overrides).map(([word, response]) => `
    <div class="override-row-cfg" data-action="edit-override" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}" data-word="${escapeHtml(word)}" data-response="${escapeHtml(response)}">
      <span>"${escapeHtml(word)}" &rarr; ${escapeHtml(response)}</span>
    </div>
  `).join("") || `<span class="config-empty">none</span>`;

  return `
    <div class="config-block">
      <h4>Callout response overrides</h4>
      <div class="override-list-cfg">${rows}</div>
      <div class="config-add-form">
        <input type="text" class="override-word-input" placeholder="word" />
        <input type="text" class="override-response-input" placeholder="response" />
        <button data-action="add-override" data-scope="${scope}" data-bot="${escapeHtml(botKey || "")}">Add</button>
      </div>
    </div>
  `;
}

function commonConfigSectionHtml(config) {
  return `
    ${chipListBlockHtml("common", null, "Excluded users", "excluded-users", "user", config.excludedUsers)}
    ${chipListBlockHtml("common", null, "Callout words", "callout-words", "word", config.calloutWords)}
    ${chipListBlockHtml("common", null, "Forbidden words", "forbidden-words", "word", config.forbiddenWords)}
    ${overrideListBlockHtml("common", null, config.calloutResponseOverrides)}
  `;
}

async function refreshCommonConfigPanel() {
  const container = document.getElementById("commonConfigBody");
  if (!container) return;
  const config = await api("/common-config");
  container.innerHTML = commonConfigSectionHtml(config);
}

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

function helloTriggerRowHtml(botKey, trigger, index) {
  return `
    <div class="hello-trigger-row" data-index="${index}">
      <select class="hello-match-type" data-index="${index}">
        ${Object.entries(MATCH_TYPE_LABELS).map(([v, label]) => `<option value="${v}" ${trigger.matchType === v ? "selected" : ""}>${label}</option>`).join("")}
      </select>
      <input type="text" class="hello-value" data-index="${index}" value="${escapeHtml(trigger.value)}" placeholder="text - use {ref} for the bot's name" />
      <input type="text" class="hello-also" data-index="${index}" value="${escapeHtml(trigger.alsoContains || "")}" placeholder="also contains (optional)" />
      <button data-action="save-hello-trigger" data-bot="${escapeHtml(botKey)}" data-index="${index}" title="Save">&#10003;</button>
      <button data-action="remove-hello-trigger" data-bot="${escapeHtml(botKey)}" data-index="${index}" title="Remove">&times;</button>
    </div>
  `;
}

function helloTriggersBlockHtml(botKey, triggers) {
  const rows = triggers.map((t, i) => helloTriggerRowHtml(botKey, t, i)).join("") || `<div class="config-empty">none</div>`;
  return `
    <div class="config-block">
      <h4>Hello triggers</h4>
      <div class="hello-triggers-list">${rows}</div>
      <div class="hello-trigger-row hello-trigger-new">
        <select class="hello-match-type-new">
          ${Object.entries(MATCH_TYPE_LABELS).map(([v, label]) => `<option value="${v}">${label}</option>`).join("")}
        </select>
        <input type="text" class="hello-value-new" placeholder="text - use {ref} for the bot's name" />
        <input type="text" class="hello-also-new" placeholder="also contains (optional)" />
        <button data-action="add-hello-trigger" data-bot="${escapeHtml(botKey)}">Add</button>
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

botsPanelEl.addEventListener("click", async (e) => {
  const target = e.target.closest("[data-action]");
  if (!target) return;
  const botKey = target.dataset.bot;
  const panel = target.closest(".config-panel");
  const block = target.closest(".config-block");

  switch (target.dataset.action) {
    case "toggle-section": {
      const card = target.closest(".bot-card, .overrides-card");
      if (card) card.classList.toggle("collapsed");
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
    case "edit-override": {
      const { scope, word, response } = target.dataset;
      const prefix = scopedPrefix(scope, botKey);
      openEditModal({
        title: "Edit callout response",
        fields: [
          { id: "word", label: "Word", value: word },
          { id: "response", label: "Response", value: response },
        ],
        onSave: async (values) => {
          const newWord = values.word.trim().toLowerCase();
          const newResponse = values.response.trim();
          if (!newWord || !newResponse) return;
          await api(`${prefix}/callout-overrides`, { method: "POST", body: JSON.stringify({ word: newWord, response: newResponse }) });
          if (newWord !== word.toLowerCase()) {
            await api(`${prefix}/callout-overrides/${encodeURIComponent(word)}`, { method: "DELETE" });
          }
          await refreshScoped(scope, botKey);
        },
        onDelete: async () => {
          await api(`${prefix}/callout-overrides/${encodeURIComponent(word)}`, { method: "DELETE" });
          await refreshScoped(scope, botKey);
        },
      });
      break;
    }
    case "add-override": {
      const { scope } = target.dataset;
      const word = block.querySelector(".override-word-input").value.trim();
      const response = block.querySelector(".override-response-input").value.trim();
      if (!word || !response) return;
      await api(`${scopedPrefix(scope, botKey)}/callout-overrides`, { method: "POST", body: JSON.stringify({ word, response }) });
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
    case "save-hello-trigger": {
      const index = target.dataset.index;
      const row = target.closest(".hello-trigger-row");
      const matchType = row.querySelector(".hello-match-type").value;
      const value = row.querySelector(".hello-value").value.trim();
      const alsoContains = row.querySelector(".hello-also").value.trim();
      if (!value) return;
      await api(`/bots/${encodeURIComponent(botKey)}/hello-triggers/${index}`, {
        method: "POST",
        body: JSON.stringify({ matchType, value, alsoContains: alsoContains || null }),
      });
      await refreshConfigPanel(botKey);
      break;
    }
    case "remove-hello-trigger": {
      await api(`/bots/${encodeURIComponent(botKey)}/hello-triggers/${target.dataset.index}`, { method: "DELETE" });
      await refreshConfigPanel(botKey);
      break;
    }
    case "add-hello-trigger": {
      const matchType = block.querySelector(".hello-match-type-new").value;
      const value = block.querySelector(".hello-value-new").value.trim();
      const alsoContains = block.querySelector(".hello-also-new").value.trim();
      if (!value) return;
      await api(`/bots/${encodeURIComponent(botKey)}/hello-triggers`, {
        method: "POST",
        body: JSON.stringify({ matchType, value, alsoContains: alsoContains || null }),
      });
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
  events.forEach(recordEvent);
  renderFeed();
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
  await Promise.all([loadBots(), loadInitialFeed(), loadMockMode(), loadOverrides(), refreshCommonConfigPanel()]);
  // loadBots and loadInitialFeed race independently, so re-sync recent-message cards once
  // both have definitely finished, in case events arrived before their bot's card existed.
  bots.forEach((bot) => renderRecentMessages(bot.key));
  connectWebSocket();
  setInterval(refreshCooldowns, 1000);
}

init();

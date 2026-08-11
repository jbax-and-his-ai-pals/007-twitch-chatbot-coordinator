const feedEl = document.getElementById("feed");
const botCardsEl = document.getElementById("botCards");
const mockSwitch = document.getElementById("mockSwitch");
const mockLabel = document.getElementById("mockLabel");
const connStatus = document.getElementById("connStatus");
const showAiCalls = document.getElementById("showAiCalls");
const overridesListEl = document.getElementById("overridesList");

const COOLDOWN_TYPES = ["mainline", "wisdom", "callout", "retry", "request"];
let bots = [];

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

function appendFeedEntry(evt) {
  if (evt.type === "ai_call" && !showAiCalls.checked) return;

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
  `;

  card.querySelector("form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const input = card.querySelector("input");
    const message = input.value.trim();
    if (!message) return;
    await api("/send", { method: "POST", body: JSON.stringify({ botKey: bot.key, message }) });
    input.value = "";
  });

  return card;
}

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
  connectWebSocket();
  setInterval(refreshCooldowns, 1000);
}

init();

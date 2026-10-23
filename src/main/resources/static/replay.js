const botSelect = document.getElementById("replayBotSelect");
const daysInput = document.getElementById("replayDays");
const limitInput = document.getElementById("replayLimit");
const runsInput = document.getElementById("replayRuns");
const currentModelEl = document.getElementById("currentModelEl");
const previewBtn = document.getElementById("previewCandidatesBtn");
const runBtn = document.getElementById("runReplayBtn");
const statusEl = document.getElementById("replayStatus");
const previewEl = document.getElementById("replayPreview");
const resultsEl = document.getElementById("replayResults");

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);
}

async function api(path, options) {
  const res = await fetch(`/api${path}`, { headers: { "Content-Type": "application/json" }, ...options });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function loadControls() {
  const [bots, models] = await Promise.all([api("/bots"), api("/models")]);
  botSelect.innerHTML = bots.map((b) => `<option value="${escapeHtml(b.key)}">${escapeHtml(b.key)}</option>`).join("");
  currentModelEl.textContent = models.selected || "(none configured)";
}

function params() {
  return {
    botKey: botSelect.value,
    days: Math.max(1, Math.min(90, Number(daysInput.value) || 7)),
    limit: Math.max(1, Math.min(50, Number(limitInput.value) || 10)),
    runsPerModel: Math.max(1, Math.min(10, Number(runsInput.value) || 3)),
  };
}

function candidateHtml(c) {
  const time = new Date(c.timestampMs).toLocaleString();
  return `
    <div class="quality-response-card">
      <div class="quality-response-header">
        <span class="quality-bot">${escapeHtml(c.botKey)}</span>
        <span class="quality-time">${time}</span>
      </div>
      <div class="quality-field"><span class="quality-field-label">original message:</span> ${escapeHtml(c.sourceMessage)}</div>
      <div class="quality-field"><span class="quality-field-label">shipped response:</span> ${escapeHtml(c.originalResponse)}</div>
    </div>
  `;
}

async function previewCandidates() {
  const { botKey, days, limit } = params();
  previewBtn.disabled = true;
  statusEl.textContent = "Loading messages...";
  try {
    const candidates = await api(`/replay/candidates?botKey=${encodeURIComponent(botKey)}&days=${days}&limit=${limit}`);
    previewEl.innerHTML = candidates.length
      ? candidates.map(candidateHtml).join("")
      : `<p class="quality-empty">No transformed messages found for this bot in that window.</p>`;
    previewEl.style.display = "block";
    statusEl.textContent = `${candidates.length} message${candidates.length === 1 ? "" : "s"} found.`;
  } catch (e) {
    statusEl.textContent = "Failed to load messages - see console.";
    console.error(e);
  } finally {
    previewBtn.disabled = false;
  }
}

function runHtml(run) {
  if (run.errorMessage) {
    return `<div class="replay-run replay-run-error">run ${run.runIndex} (${run.durationMs}ms): ${escapeHtml(run.errorMessage)}</div>`;
  }
  return `<div class="replay-run">run ${run.runIndex} (${run.durationMs}ms): ${escapeHtml(run.response)}</div>`;
}

function runCardHtml(result, modelKey) {
  const c = result.candidate;
  const time = new Date(c.timestampMs).toLocaleString();

  return `
    <div class="quality-response-card replay-result-card">
      <div class="quality-response-header">
        <span class="quality-bot">${escapeHtml(c.botKey)}</span>
        <span class="quality-time">${time}</span>
      </div>
      <div class="quality-field"><span class="quality-field-label">original message:</span> ${escapeHtml(c.sourceMessage)}</div>
      <div class="quality-field"><span class="quality-field-label">shipped response:</span> ${escapeHtml(c.originalResponse)}</div>
      <div class="replay-models-results">
        <div class="replay-model-section">
          <div class="replay-model-name">${escapeHtml(modelKey)}</div>
          ${result.runs.map(runHtml).join("")}
        </div>
      </div>
    </div>
  `;
}

async function runReplay() {
  const { botKey, days, limit, runsPerModel } = params();

  runBtn.disabled = true;
  previewBtn.disabled = true;
  statusEl.textContent = "Running replay against the currently selected model - may take a while...";
  resultsEl.innerHTML = "";

  try {
    const models = await api("/models");
    const modelKey = models.selected;
    if (!modelKey) {
      statusEl.textContent = "No model is currently selected in the control panel.";
      return;
    }

    const results = await api("/replay/run", {
      method: "POST",
      body: JSON.stringify({ botKey, days, limit, runsPerModel }),
    });
    resultsEl.innerHTML = results.length
      ? results.map((r) => runCardHtml(r, modelKey)).join("")
      : `<p class="quality-empty">No transformed messages found for this bot in that window.</p>`;
    statusEl.textContent = `Done - ${results.length} message${results.length === 1 ? "" : "s"} replayed against ${modelKey}.`;
  } catch (e) {
    statusEl.textContent = "Replay failed - see console.";
    console.error(e);
  } finally {
    runBtn.disabled = false;
    previewBtn.disabled = false;
  }
}

previewBtn.addEventListener("click", previewCandidates);
runBtn.addEventListener("click", runReplay);

loadControls();

const daysInput = document.getElementById("qualityDays");
const generateBtn = document.getElementById("generateReportBtn");
const statusEl = document.getElementById("qualityStatus");
const reportEl = document.getElementById("qualityReport");
const summaryEl = document.getElementById("qualitySummary");
const recommendationEl = document.getElementById("qualityRecommendation");
const bestEl = document.getElementById("qualityBest");
const worstEl = document.getElementById("qualityWorst");

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);
}

const CLASSIFICATION_LABELS = {
  EXPLICIT_YES: "explicit yes",
  EXPLICIT_NO: "explicit no",
  EXPLICIT_WHY: "explicit why",
  KEYWORD_POSITIVE: "positive reaction",
  KEYWORD_NEGATIVE: "negative reaction",
};

const CLASSIFICATION_CLASS = {
  EXPLICIT_YES: "quality-tag-positive",
  KEYWORD_POSITIVE: "quality-tag-positive",
  EXPLICIT_NO: "quality-tag-negative",
  KEYWORD_NEGATIVE: "quality-tag-negative",
  EXPLICIT_WHY: "quality-tag-neutral",
};

function reactionHtml(reaction) {
  const cls = CLASSIFICATION_CLASS[reaction.classification] || "quality-tag-neutral";
  const label = CLASSIFICATION_LABELS[reaction.classification] || reaction.classification;
  return `<div class="quality-reaction"><span class="quality-tag ${cls}">${label}</span> <span class="quality-reaction-user">${escapeHtml(reaction.user)}</span>: ${escapeHtml(reaction.message)}</div>`;
}

function responseCardHtml(item) {
  const time = new Date(item.timestampMs).toLocaleString();
  const scoreClass = item.score > 0 ? "quality-score-positive" : item.score < 0 ? "quality-score-negative" : "";
  const sourceUserHtml = item.sourceUser ? ` <span class="quality-source-user">- ${escapeHtml(item.sourceUser)}</span>` : "";

  return `
    <div class="quality-response-card">
      <div class="quality-response-header">
        <span class="quality-bot">${escapeHtml(item.botKey)}</span>
        <span class="quality-score ${scoreClass}">score: ${item.score}</span>
        <span class="quality-time">${time}</span>
      </div>
      <div class="quality-field"><span class="quality-field-label">chat said:</span> ${escapeHtml(item.sourceMessage || "(unknown)")}${sourceUserHtml}</div>
      <div class="quality-field"><span class="quality-field-label">bot replied:</span> ${escapeHtml(item.response)}</div>
      <div class="quality-reactions">${item.reactions.map(reactionHtml).join("")}</div>
    </div>
  `;
}

function renderReport(report) {
  summaryEl.innerHTML = `
    <div class="quality-summary-row"><span>Window</span><span>${escapeHtml(report.fromDate)} &rarr; ${escapeHtml(report.toDate)} (${report.days} day${report.days === 1 ? "" : "s"})</span></div>
    <div class="quality-summary-row"><span>AI-generated responses analyzed</span><span>${report.totalCandidateResponses}</span></div>
    <div class="quality-summary-row"><span>Responses with a direct-reference reaction</span><span>${report.respondedCount}</span></div>
    <div class="quality-summary-row"><span>Explicit yes / no / why</span><span>${report.explicitYesCount} / ${report.explicitNoCount} / ${report.explicitWhyCount}</span></div>
  `;

  recommendationEl.innerHTML = report.recommendation
    ? `<h2>Recommendation</h2><p class="quality-recommendation-text">${escapeHtml(report.recommendation)}</p>`
    : `<h2>Recommendation</h2><p class="quality-empty">Not enough reaction data yet to make a recommendation.</p>`;

  bestEl.innerHTML = report.bestResponses.length
    ? report.bestResponses.map(responseCardHtml).join("")
    : `<p class="quality-empty">No positively-received responses found in this window.</p>`;

  worstEl.innerHTML = report.worstResponses.length
    ? report.worstResponses.map(responseCardHtml).join("")
    : `<p class="quality-empty">No negatively-received responses found in this window.</p>`;

  reportEl.style.display = "block";
}

async function generateReport() {
  const days = Math.max(1, Math.min(90, Number(daysInput.value) || 7));
  daysInput.value = days;

  generateBtn.disabled = true;
  statusEl.textContent = "Analyzing...";
  reportEl.style.display = "none";

  try {
    const res = await fetch(`/api/quality/report?days=${days}`);
    if (!res.ok) {
      statusEl.textContent = `Failed to generate report (HTTP ${res.status})`;
      return;
    }
    renderReport(await res.json());
    statusEl.textContent = "";
  } catch (e) {
    statusEl.textContent = "Failed to generate report - see console.";
    console.error(e);
  } finally {
    generateBtn.disabled = false;
  }
}

generateBtn.addEventListener("click", generateReport);

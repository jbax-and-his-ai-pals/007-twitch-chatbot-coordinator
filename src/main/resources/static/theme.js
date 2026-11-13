// Control-panel themes. Loaded in <head> on every page (before first paint, so there's no flash of the
// wrong theme); the choice lives in localStorage and applies via <html data-theme="...">.
// Adding a theme = add an entry here, plus a `html[data-theme="<id>"]` block in a stylesheet.
(function () {
  var THEMES = [
    { id: "dark", label: "Dark (default)" },
    { id: "geocities", label: "GeoCities '97" },
  ];
  var KEY = "controlPanelTheme";

  function stored() {
    try { return localStorage.getItem(KEY); } catch (e) { return null; }
  }
  function valid(id) {
    return THEMES.some(function (t) { return t.id === id; });
  }
  function current() {
    var id = stored();
    return valid(id) ? id : "dark";
  }
  function apply(id) {
    document.documentElement.setAttribute("data-theme", id);
    try { localStorage.setItem(KEY, id); } catch (e) { /* private mode etc. - theme just won't persist */ }
    decorate(id);
  }

  // --- GeoCities extras: the things CSS alone can't add (a banner, a hit counter, "badges") ---
  var DECOR_ID = "geocities-decor";
  var BANNER_ID = "geocities-banner";

  function hitCount() {
    var n = 1337;
    try {
      n = parseInt(localStorage.getItem("geocitiesHits") || "1336", 10) + 1;
      localStorage.setItem("geocitiesHits", String(n));
    } catch (e) { /* fine */ }
    return String(n).padStart(6, "0");
  }

  function decorate(id) {
    [DECOR_ID, BANNER_ID].forEach(function (x) {
      var el = document.getElementById(x);
      if (el) el.remove();
    });
    if (id !== "geocities" || !document.body) return;

    var banner = document.createElement("div");
    banner.id = BANNER_ID;
    banner.className = "gc-banner";
    banner.setAttribute("aria-hidden", "true");
    banner.innerHTML = '<span class="gc-marquee">~*~ Welcome to the BLAMPUTTS Control Panel!!! ~*~ ' +
      'You are visitor number one million (give or take) ~*~ Please sign my guestbook ~*~ ' +
      'Remember: no all caps in chat, except when the bot does it ~*~ ' +
      'Best viewed in Netscape Navigator at 800x600 ~*~</span>';
    document.body.insertBefore(banner, document.body.firstChild);

    var foot = document.createElement("footer");
    foot.id = DECOR_ID;
    foot.className = "gc-footer";
    foot.innerHTML =
      '<span class="gc-construction">UNDER CONSTRUCTION</span>' +
      '<span class="gc-counter-wrap">You are visitor # <span class="gc-counter">' + hitCount() + '</span></span>' +
      '<span class="gc-badge">Best viewed in Netscape 4.0</span>' +
      '<span class="gc-badge gc-badge-alt">Made with Notepad</span>' +
      '<span class="gc-blink">NEW!</span>' +
      '<span class="gc-email">Webmaster: blampbot@geocities.example</span>';
    document.body.appendChild(foot);
  }

  function buildSelect() {
    var sel = document.getElementById("themeSelect");
    if (!sel) {
      var bar = document.querySelector(".topbar");
      if (!bar) return;
      sel = document.createElement("select");
      sel.id = "themeSelect";
      sel.className = "theme-select";
      sel.title = "Control panel theme";
      var slot = bar.querySelector(".mock-toggle") || bar;
      slot.insertBefore(sel, slot.firstChild);
    }
    sel.innerHTML = THEMES.map(function (t) {
      return '<option value="' + t.id + '">' + t.label + "</option>";
    }).join("");
    sel.value = current();
    sel.addEventListener("change", function () { apply(sel.value); });
  }

  // Set the attribute immediately (we're in <head>); build the UI once the DOM exists.
  document.documentElement.setAttribute("data-theme", current());
  function ready() {
    buildSelect();
    decorate(current());
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", ready);
  else ready();
})();

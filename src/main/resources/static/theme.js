// Control-panel themes. Loaded in <head> on every page (before first paint, so there's no flash of the
// wrong theme); the choice lives in localStorage and applies via <html data-theme="...">.
//
// Adding a theme: add an entry to THEMES, an optional entry to DECOR (a banner above and/or a footer below
// the page - the things CSS alone can't add), and a `html[data-theme="<id>"]` block in a stylesheet
// (theme-geocities.css or theme-retro.css). Everything is homage in the generic sense: no logos, no
// brand artwork, no external fonts or images - just palettes, borders and text.
(function () {
  var THEMES = [
    { id: "dark", label: "Dark (default)" },
    { id: "geocities", label: "GeoCities '97" },
    { id: "dos", label: "DOS text mode" },
    { id: "win31", label: "Windows 3.1-era desktop" },
    { id: "apple2", label: "Green phosphor terminal" },
    { id: "amber", label: "Amber CRT terminal" },
    { id: "micro8", label: "8-bit home computer (blue)" },
    { id: "lcd", label: "Handheld LCD (4 shades)" },
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

  // --- decorations: { banner: {cls, html}, footer: {cls, html} } per theme ---
  var BANNER_ID = "theme-banner";
  var FOOTER_ID = "theme-footer";

  function hitCount() {
    var n = 1337;
    try {
      n = parseInt(localStorage.getItem("geocitiesHits") || "1336", 10) + 1;
      localStorage.setItem("geocitiesHits", String(n));
    } catch (e) { /* fine */ }
    return String(n).padStart(6, "0");
  }

  var CURSOR = '<span class="rt-cursor">&#9608;</span>';

  var DECOR = {
    geocities: function () {
      return {
        banner: {
          cls: "gc-banner",
          html: '<span class="gc-marquee">~*~ Welcome to the BLAMPUTTS Control Panel!!! ~*~ ' +
            'You are visitor number one million (give or take) ~*~ Please sign my guestbook ~*~ ' +
            'Remember: no all caps in chat, except when the bot does it ~*~ ' +
            'Best viewed in Netscape Navigator at 800x600 ~*~</span>',
        },
        footer: {
          cls: "gc-footer",
          html:
            '<span class="gc-construction">UNDER CONSTRUCTION</span>' +
            '<span class="gc-counter-wrap">You are visitor # <span class="gc-counter">' + hitCount() + "</span></span>" +
            '<span class="gc-badge">Best viewed in Netscape 4.0</span>' +
            '<span class="gc-badge gc-badge-alt">Made with Notepad</span>' +
            '<span class="gc-blink">NEW!</span>' +
            '<span class="gc-email">Webmaster: blampbot@geocities.example</span>',
        },
      };
    },
    dos: function () {
      return {
        banner: {
          cls: "rt-banner rt-menubar",
          html: "<span><u>F</u>ile</span><span><u>E</u>dit</span><span><u>V</u>iew</span><span><u>S</u>earch</span>" +
            "<span><u>R</u>un</span><span><u>H</u>elp</span>",
        },
        footer: {
          cls: "rt-footer",
          html: "<span>C:&#92;BLAMPUTTS&gt;" + CURSOR + "</span><span class='rt-spacer'></span><span>640K OK</span>",
        },
      };
    },
    win31: function () {
      return {
        banner: {
          cls: "rt-banner rt-menubar",
          html: "<span><u>F</u>ile</span><span><u>O</u>ptions</span><span><u>W</u>indow</span><span><u>H</u>elp</span>",
        },
      };
    },
    apple2: function () {
      return {
        banner: {
          cls: "rt-banner",
          html: "<span>BLAMPUTTS CONTROL PANEL</span><span class='rt-spacer'></span><span>48K RAM SYSTEM</span>",
        },
        footer: { cls: "rt-footer", html: "<span>]RUN BLAMPUTTS" + CURSOR + "</span>" },
      };
    },
    amber: function () {
      return {
        banner: {
          cls: "rt-banner",
          html: "<span>*** BLAMPUTTS TERMINAL ***</span><span class='rt-spacer'></span><span>LINE: ONLINE &nbsp; 1200 BAUD</span>",
        },
        footer: { cls: "rt-footer", html: "<span>LOGIN:" + CURSOR + "</span>" },
      };
    },
    micro8: function () {
      return {
        banner: {
          cls: "rt-banner rt-center",
          html: "<span>**** BLAMPUTTS BASIC V2 ****</span><span>64K RAM SYSTEM &nbsp; 38911 BASIC BYTES FREE</span>",
        },
        footer: { cls: "rt-footer", html: "<span>READY." + CURSOR + "</span>" },
      };
    },
    lcd: function () {
      return {
        banner: {
          cls: "rt-banner",
          html: "<span>BLAMPUTTS</span><span class='rt-spacer'></span><span>SCORE 000000 &nbsp; HI 037350</span>",
        },
        footer: { cls: "rt-footer rt-center", html: "<span class='rt-blink'>PRESS START</span>" },
      };
    },
  };

  function decorate(id) {
    [BANNER_ID, FOOTER_ID].forEach(function (x) {
      var el = document.getElementById(x);
      if (el) el.remove();
    });
    var make = DECOR[id];
    if (!make || !document.body) return;
    var d = make();
    if (d.banner) {
      var b = document.createElement("div");
      b.id = BANNER_ID;
      b.className = d.banner.cls;
      b.setAttribute("aria-hidden", "true");
      b.innerHTML = d.banner.html;
      document.body.insertBefore(b, document.body.firstChild);
    }
    if (d.footer) {
      var f = document.createElement("footer");
      f.id = FOOTER_ID;
      f.className = d.footer.cls;
      f.setAttribute("aria-hidden", "true");
      f.innerHTML = d.footer.html;
      document.body.appendChild(f);
    }
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

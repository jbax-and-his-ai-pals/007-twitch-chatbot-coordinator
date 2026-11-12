#!/usr/bin/env python3
"""Compare local Ollama models against the responses a production model really gave.

Mines data/logs/*.jsonl for real (non-mocked) AI calls, replays each logged prompt against
local models, and builds a self-contained HTML report. The baseline is simply the response
that was logged at the time - this script NEVER calls Vertex/Gemini or any paid/cloud model:
every request goes to a local Ollama server and the host is asserted to be localhost.

    python scripts/model_comparison.py sample [--seed 7 --transforms 10 --other 5 --days 21]
    python scripts/model_comparison.py run gemma4:12b qwen3.5:9b ...   [--runs 3 --runs-other 2]
    python scripts/model_comparison.py report

Output lives in data/model-comparison/ (gitignored: it contains real chat messages/usernames).
"""
import argparse
import datetime
import glob
import html
import json
import os
import random
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOG_DIR = ROOT / "data" / "logs"
OUT = ROOT / "data" / "model-comparison"
CONFIG = ROOT / "src" / "main" / "resources" / "config.yaml"
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434")


def assert_local_only(url, model=None):
    host = urllib.parse.urlparse(url).hostname
    if host not in ("localhost", "127.0.0.1", "::1"):
        sys.exit(f"refusing to call non-local host {host!r}: this tool only talks to local Ollama")
    if model and (model.lower().startswith(("gemini", "gpt", "claude")) or "cloud" in model.lower()):
        sys.exit(f"refusing model {model!r}: only local, free models are allowed")


# ---------------------------------------------------------------- production-logic ports
# Mirrors core/Levenshtein.kt, core/WordCasing.kt, core/Phrases.kt and
# core/engine/{TransformedMessage,CasingRestorer}.kt so "would this have shipped" is judged
# by the same rules production applies.

def similarity(a, b):
    la, lb = len(a), len(b)
    cost = list(range(la + 1))
    for i in range(1, lb + 1):
        new = [i] + [0] * la
        for j in range(1, la + 1):
            match = 0 if a[j - 1].lower() == b[i - 1].lower() else 1
            new[j] = min(cost[j] + 1, new[j - 1] + 1, cost[j - 1] + match)
        cost = new
    m = max(la, lb)
    return 100 if m == 0 else (100 * (m - cost[la])) // m


EMOJI_RE = re.compile(r"\b[a-z]\S*[A-Z]\S*\b")


def contains_emoji(s):
    return any(EMOJI_RE.search(tok) for tok in s.split(" "))


def word_casing(word):
    letters = [c for c in word if c.isalpha()]
    if not letters or all(c.islower() for c in letters):
        return "LOWER"
    if all(c.isupper() for c in letters):
        return "UPPER"
    if letters[0].isupper() and all(c.islower() for c in letters[1:]):
        return "TITLE"
    return "MIXED"


def recase(original_word, replacement):
    c = word_casing(original_word)
    if c == "UPPER":
        return replacement.upper()
    if c == "TITLE":
        return replacement if not replacement else replacement[0].upper() + replacement[1:].lower()
    if c == "LOWER":
        return replacement
    return None


def restore_casing(original, transformed):
    ow, tw = original.split(" "), transformed.split(" ")
    if len(ow) != len(tw):
        return transformed
    out = []
    for o, t in zip(ow, tw):
        out.append(o if o.lower() == t.lower() else (recase(o, t) or o))
    return " ".join(out)


def clean(raw):
    t = raw.strip()
    return t[1:-1] if len(t) >= 2 and t.startswith('"') and t.endswith('"') else t


def validate_transform(original, raw, word, marker, threshold, forbidden):
    transformed = restore_casing(original, clean(raw))
    sim = similarity(original, transformed)
    low = transformed.lower()
    reasons = []
    if not transformed:
        reasons.append("empty")
    if low == original.lower():
        reasons.append("unchanged")
    if contains_emoji(transformed):
        reasons.append("emote-shaped")
    if any(f in low for f in forbidden):
        reasons.append("forbidden word")
    if marker and marker.lower() in low:
        reasons.append("quotes itself")
    if sim < threshold:
        reasons.append("too different")
    if transformed.startswith("!"):
        reasons.append("starts with !")
    if word.lower() not in low:
        reasons.append("missing word")
    return {"ok": not reasons, "text": transformed, "sim": sim, "reasons": reasons}


LEAK_MARKERS = ("preserve the exact capitalization", "here are your instructions", "you are toikeybot",
                "you are buttsbot", "you are blampbot", "you are neo")


def validate_other(kind, item, raw):
    text = raw.strip()
    reasons = []
    if not text:
        reasons.append("empty")
    low = text.lower()
    if any(m in low for m in LEAK_MARKERS):
        reasons.append("leaked instructions")
    if kind == "hello" and item.get("user") and item["user"].lower() not in low:
        reasons.append("missing username")
    if kind == "request" and len(text) > 150:
        reasons.append(f"over 150 chars ({len(text)})")
    return {"ok": not reasons, "text": text, "sim": None, "reasons": reasons}


# ---------------------------------------------------------------- log mining

def classify(prompt):
    p = prompt or ""
    if "here's the string:" in p and p.rstrip().endswith('"'):
        # (a historical config bug sent transforms with NO instruction and no "here's the
        # string:" marker - those fall through to "other" and are skipped)
        return "transform"
    if "here's the request" in p:
        return "request"
    if "witticism" in p:
        return "wisdom"
    if "include this user's name" in p:
        return "hello"
    return "other"


def load_events():
    events = []
    for f in sorted(glob.glob(str(LOG_DIR / "activity-*.jsonl"))):
        for line in open(f, encoding="utf-8"):
            line = line.strip()
            if line:
                try:
                    events.append(json.loads(line))
                except ValueError:
                    pass
    return events


def parse_config():
    """Tiny line scanner (no PyYAML): per-bot self_quote_marker / similarity / forbidden_words_add."""
    cfg = {"default_threshold": 70, "bots": {}}
    if not CONFIG.exists():
        return cfg
    lines = CONFIG.read_text(encoding="utf-8").splitlines()
    section, bot, sub = None, None, None
    for ln in lines:
        s = ln.strip()
        if not s or s.startswith("#"):
            continue
        indent = len(ln) - len(ln.lstrip())
        if indent == 0:
            section, bot, sub = s.rstrip(":"), None, None
        elif section == "defaults" and indent == 4 and s.startswith("similarity_threshold:"):
            cfg["default_threshold"] = int(s.split(":")[1])
        elif section == "bots" and indent == 4 and s.endswith(":"):
            bot, sub = s[:-1], None
            cfg["bots"][bot] = {"marker": "", "threshold": None, "forbidden": []}
        elif section == "bots" and bot:
            b = cfg["bots"][bot]
            if indent == 8 and s.startswith("similarity_threshold:"):
                b["threshold"] = int(s.split(":")[1])
            elif indent == 12 and s.startswith("self_quote_marker:"):
                b["marker"] = s.split(":", 1)[1].strip().strip('"')
            elif indent == 8:
                sub = s.rstrip(":") if s.endswith(":") else None
            elif sub == "forbidden_words_add" and s.startswith("- "):
                b["forbidden"].append(s[2:].strip().strip('"').lower())
    return cfg


def build_items(events, cfg):
    outgoing = [e for e in events if e.get("type") == "outgoing" and not e.get("manual")]
    items = []
    for e in events:
        if e.get("type") != "ai_call" or e.get("mocked") is not False:
            continue
        prompt, resp, bot = e.get("prompt") or "", e.get("response") or "", e.get("botKey")
        kind = classify(prompt)
        if kind == "other" or not resp.strip():
            continue
        item = {"kind": kind, "bot": bot, "ts": e["timestampMs"], "prompt": prompt, "baseline": resp}
        if kind == "transform":
            m = re.search(r'casing; "(.*)"\s*$', prompt, re.S)
            w = re.search(r"don't repeat (\S+) over and over", prompt)
            if not (m and w):
                continue
            item["original"], item["word"] = m.group(1), w.group(1).lower()
            bcfg = cfg["bots"].get(bot, {})
            item["marker"] = bcfg.get("marker", "")
            item["threshold"] = bcfg.get("threshold") or cfg["default_threshold"]
            item["forbidden"] = bcfg.get("forbidden", [])
            v = validate_transform(item["original"], resp, item["word"], item["marker"], item["threshold"], item["forbidden"])
            item["baseline_valid"] = v["ok"]
            item["shipped"] = any(
                o.get("botKey") == bot and 0 <= o["timestampMs"] - e["timestampMs"] <= 180_000
                and o.get("message") == v["text"] for o in outgoing)
        elif kind == "hello":
            m = re.search(r'user\'s name: "(.*)"\s*$', prompt, re.S)
            item["user"] = m.group(1) if m else ""
        elif kind == "request":
            m = re.search(r"here's the request:\s*\"(.*)\"\s*$", prompt, re.S)
            item["original"] = m.group(1) if m else ""
        items.append(item)
    return items


def cmd_sample(a):
    events = load_events()
    cfg = parse_config()
    items = build_items(events, cfg)
    OUT.mkdir(parents=True, exist_ok=True)

    tr = [i for i in items if i["kind"] == "transform"]
    both = [(i["baseline_valid"], i["shipped"]) for i in tr]
    agree = sum(1 for v, s in both if v == s)
    print(f"validator-vs-shipped agreement on {len(tr)} logged transforms: {agree}/{len(tr)} "
          f"({100 * agree // max(1, len(tr))}%); validator pass {sum(v for v, _ in both)}, actually shipped {sum(s for _, s in both)}")
    disagree = {"valid_not_shipped": sum(1 for v, s in both if v and not s), "shipped_not_valid": sum(1 for v, s in both if s and not v)}
    print("disagreements:", disagree)

    cutoff = time.time() * 1000 - a.days * 86_400_000
    rng = random.Random(a.seed)
    seen, picked = set(), []

    def take(pool, n):
        pool = [i for i in pool if i["ts"] >= cutoff and i["prompt"] not in seen]
        rng.shuffle(pool)
        for i in pool[:n]:
            seen.add(i["prompt"])
            picked.append(i)

    for bot in sorted({i["bot"] for i in items}):
        take([i for i in tr if i["bot"] == bot], a.transforms)
        for kind in ("hello", "request", "wisdom"):
            take([i for i in items if i["kind"] == kind and i["bot"] == bot], a.other)
    for n, i in enumerate(sorted(picked, key=lambda x: (x["kind"], x["bot"], x["ts"]))):
        i["id"] = n
    picked.sort(key=lambda x: x["id"])
    json.dump({"agreement": {"total": len(tr), "agree": agree, **disagree}, "items": picked},
              open(OUT / "sample.json", "w", encoding="utf-8"), indent=1)
    from collections import Counter
    print("sampled:", dict(Counter((i["kind"], i["bot"]) for i in picked)))


# ---------------------------------------------------------------- running local models

def ollama(path, body, timeout=300):
    assert_local_only(OLLAMA)
    req = urllib.request.Request(OLLAMA + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def generate(model, prompt):
    body = {"model": model, "prompt": prompt, "stream": False, "think": False, "keep_alive": "10m"}
    t0 = time.perf_counter()
    try:
        try:
            d = ollama("/api/generate", body)
        except urllib.error.HTTPError as e:
            if b"think" in e.read():  # model without a thinking switch
                body.pop("think")
                d = ollama("/api/generate", body)
            else:
                raise
        wall = (time.perf_counter() - t0) * 1000
        return {"text": d.get("response", ""), "wall_ms": round(wall), "total_ms": round(d.get("total_duration", 0) / 1e6),
                "load_ms": round(d.get("load_duration", 0) / 1e6), "eval_count": d.get("eval_count", 0),
                "eval_ms": round(d.get("eval_duration", 0) / 1e6), "error": None}
    except Exception as e:  # recorded, never aborts the run
        return {"text": "", "wall_ms": round((time.perf_counter() - t0) * 1000), "error": str(e)}


def safe(model):
    return re.sub(r"[^A-Za-z0-9._-]", "_", model)


def cmd_run(a):
    for m in a.models:
        assert_local_only(OLLAMA, m)
    sample = json.load(open(OUT / "sample.json", encoding="utf-8"))["items"]
    if a.limit:  # spread a smoke test across kinds/bots instead of taking only the first few
        sample = sample[:: max(1, len(sample) // a.limit)][: a.limit]
    (OUT / "runs").mkdir(parents=True, exist_ok=True)
    for model in a.models:
        path = OUT / "runs" / f"{safe(model)}.json"
        rec = json.load(open(path, encoding="utf-8")) if path.exists() else {"model": model, "load": None, "calls": {}}
        if rec["load"] is None:
            print(f"== {model}: warm-up (load) call", flush=True)
            w = generate(model, "hi")
            rec["load"] = {k: w.get(k) for k in ("wall_ms", "load_ms", "error")}
            if w["error"]:
                print("   warm-up failed:", w["error"], flush=True)
        for item in sample:
            runs = a.runs if item["kind"] == "transform" else a.runs_other
            for r in range(runs):
                key = f"{item['id']}:{r}"
                if key in rec["calls"] and not rec["calls"][key].get("error"):
                    continue
                rec["calls"][key] = generate(model, item["prompt"])
            json.dump(rec, open(path, "w", encoding="utf-8"))
        print(f"== {model}: done ({len(rec['calls'])} calls)", flush=True)
        try:  # unload so the next model's load time and VRAM are clean
            ollama("/api/generate", {"model": model, "keep_alive": 0}, timeout=60)
        except Exception:
            pass


# ---------------------------------------------------------------- report

def pct(n, d):
    return None if not d else round(100 * n / d)


def evaluate(item, raw):
    if item["kind"] == "transform":
        return validate_transform(item["original"], raw, item["word"], item["marker"], item["threshold"], item["forbidden"])
    return validate_other(item["kind"], item, raw)


def cmd_report(a):
    data = json.load(open(OUT / "sample.json", encoding="utf-8"))
    items = data["items"]
    models = []
    for p in sorted((OUT / "runs").glob("*.json")):
        rec = json.load(open(p, encoding="utf-8"))
        rows = []
        for item in items:
            outs = []
            for r in range(a_runs(rec, item)):
                c = rec["calls"].get(f"{item['id']}:{r}")
                if c is None:
                    continue
                v = evaluate(item, c["text"]) if not c.get("error") else {"ok": False, "text": "", "sim": None, "reasons": ["error: " + c["error"][:80]]}
                outs.append({"text": v["text"], "ok": v["ok"], "reasons": v["reasons"], "sim": v["sim"], "ms": c.get("total_ms") or c["wall_ms"],
                             "tps": round(c["eval_count"] / (c["eval_ms"] / 1000), 1) if c.get("eval_ms") else None})
            rows.append(outs)
        models.append({"name": rec["model"], "load": rec["load"], "rows": rows})
    baseline = []
    for item in items:
        v = evaluate(item, item["baseline"])
        baseline.append({"text": v["text"], "ok": v["ok"], "reasons": v["reasons"], "sim": v["sim"], "shipped": item.get("shipped")})
    payload = {"generated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"), "agreement": data["agreement"],
               "items": [{k: i[k] for k in ("id", "kind", "bot", "ts", "original", "user", "word") if k in i} for i in items],
               "baseline": baseline, "models": models}
    template = (Path(__file__).parent / "model_comparison_report.html").read_text(encoding="utf-8")
    html_out = template.replace("/*DATA*/null", json.dumps(payload).replace("</", "<" + chr(92) + "/"))
    (OUT / "report.html").write_text(html_out, encoding="utf-8")
    print("wrote", OUT / "report.html")


def a_runs(rec, item):
    return max([int(k.split(":")[1]) + 1 for k in rec["calls"] if k.split(":")[0] == str(item["id"])] or [0])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sample")
    s.add_argument("--seed", type=int, default=7)
    s.add_argument("--days", type=int, default=21)
    s.add_argument("--transforms", type=int, default=10, help="transforms per bot")
    s.add_argument("--other", type=int, default=5, help="hello/request/wisdom prompts per bot, per type")
    r = sub.add_parser("run")
    r.add_argument("models", nargs="+")
    r.add_argument("--runs", type=int, default=3, help="runs per transform prompt")
    r.add_argument("--limit", type=int, default=0, help="smoke test: only this many evenly spaced prompts")
    r.add_argument("--runs-other", type=int, default=2, help="runs per hello/request prompt")
    sub.add_parser("report")
    a = ap.parse_args()
    {"sample": cmd_sample, "run": cmd_run, "report": cmd_report}[a.cmd](a)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Mine the activity logs for what chat actually liked: correlate every posted transform with
chat's reaction in the minute and a half afterwards, then see which features of a swap go with
laughs.  Offline and read-only - no model calls, nothing sent anywhere.

    python scripts/log_review.py [--window 90] [--gemma-since "2026-11-11 18:57"]

Writes data/log-review/review.json (gitignored: it holds real chat).  Reaction signals:
  laugh    messages from other chatters with laugh emotes/words (LUL, KEKW, lol, lmao, haha...)
  direct   messages naming a bot ("buttsbot yes", "@toikeybot lol", ...)
  author   the original author talking again within the window
and a lift versus the channel's ordinary laugh rate, since a busy minute laughs more anyway.
"""
import argparse
import bisect
import datetime
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_comparison as mc  # noqa: E402
import prompt_lab as pl  # noqa: E402

LAUGH = re.compile(r"\b(lul+w?|kekw?|omegalul|lmao+|lmfao|rofl|ha(ha)+h?|hehe+|lo+l|icant|dead|pepelaugh|\w*laugh\w*)\b|[😂🤣💀]", re.I)
BOTS = {"neoblampbot", "neobuttsbot", "toikeybot", "nightbot", "buttsbot", "blampbot", "streamelements", "moobot"}
STOP = set("the a an and or but if so to of in on at for with is are was were be been am i you he she it we they me my your this that these those there here do does did have has had not no yes just very really too than then as by from up out about into over after before what who how when where why which can could would should will".split())


def parse_ts(s):
    return datetime.datetime.strptime(s, "%Y-%m-%d %H:%M").timestamp() * 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--window", type=int, default=90)
    ap.add_argument("--gemma-since", default="2026-11-11 18:57", help="local time the app went live on the local model")
    a = ap.parse_args()
    W = a.window * 1000
    gemma_from = parse_ts(a.gemma_since)

    events = mc.load_events()
    cfg = mc.parse_config()
    chat = sorted([e for e in events if e.get("type") == "incoming" and e.get("message") and e.get("user")], key=lambda e: e["timestampMs"])
    times = [e["timestampMs"] for e in chat]
    outgoing = [e for e in events if e.get("type") == "outgoing" and not e.get("manual")]

    def window(ts, channel, span=W):
        lo, hi = bisect.bisect_right(times, min(ts, ts + span)), bisect.bisect_right(times, max(ts, ts + span))
        return [m for m in chat[lo:hi] if m.get("channel") == channel and m["user"].lower() not in BOTS]

    def react(ts, channel, author):
        msgs = window(ts, channel)
        laugh = sum(1 for m in msgs if LAUGH.search(m["message"]))
        directs = [m["message"] for m in msgs if re.search(r"(buttsbot|blampbot|toikeybot)", m["message"], re.I)]
        before = sum(1 for m in window(ts, channel, -W) if LAUGH.search(m["message"]))
        yes = sum(1 for d in directs if re.search(r"\b(yes|good bot|love it)\b", d, re.I))
        no = sum(1 for d in directs if re.search(r"\b(no|bad bot|boo|cringe|why)\b", d, re.I))
        return {"laugh": laugh, "before": before, "direct": len(directs), "directs": directs[:3], "yes": yes, "no": no,
                "author": any(m["user"].lower() == (author or "").lower() for m in msgs), "msgs": len(msgs)}

    # baseline: how many laugh messages a random 90s window holds in the same channels
    base = defaultdict(list)
    for i in range(0, len(chat), 25):
        m = chat[i]
        base[m.get("channel")].append(sum(1 for x in window(m["timestampMs"] - 1, m.get("channel")) if LAUGH.search(x["message"])))
    base_rate = {c: statistics.mean(v) for c, v in base.items() if v}

    rows = []
    for it in mc.build_items(events, cfg):
        if it["kind"] != "transform" or not it.get("shipped"):
            continue
        v = mc.validate_transform(it["original"], it["baseline"], it["word"], it["marker"], it["threshold"], it["forbidden"])
        out = next((o for o in outgoing if o.get("botKey") == it["bot"] and 0 <= o["timestampMs"] - it["ts"] <= 180_000 and o.get("message") == v["text"]), None)
        if not out:
            continue
        o_toks, n_toks = it["original"].split(" "), v["text"].split(" ")
        changed = [i for i, (x, y) in enumerate(zip(o_toks, n_toks)) if x.lower() != y.lower()] if len(o_toks) == len(n_toks) else []
        ch_orig = [strip for strip in (re.sub(r"[^\w']", "", o_toks[i]).lower() for i in changed)]
        r = react(out["timestampMs"], out.get("channel"), it.get("sourceUser") or out.get("sourceUser"))
        r["lift"] = r["laugh"] - r["before"]  # laughs after the post minus laughs in the minute before it: same chat, same busyness
        rows.append({
            "bot": it["bot"], "word": it["word"], "ts": out["timestampMs"], "channel": out.get("channel"),
            "era": "gemma" if out["timestampMs"] >= gemma_from else "gemini",
            "original": it["original"], "posted": v["text"],
            "pure": pl.pure_replace(it["original"], v["text"], it["word"]),
            "partial": bool(changed) and pl.partial_word(it["original"], v["text"], it["word"]),
            "n_tokens": len(o_toks), "n_changed": len(changed),
            "pos": (changed[-1] + 1) / len(o_toks) if changed else None,
            "at_end": bool(changed) and changed[-1] >= len(o_toks) - 2,
            "stopword": bool(ch_orig) and all(c in STOP for c in ch_orig),
            "replaced": ch_orig, **{k: r[k] for k in ("laugh", "before", "direct", "directs", "yes", "no", "author", "msgs", "lift")},
        })

    def agg(label, key):
        groups = defaultdict(list)
        for r in rows:
            groups[key(r)].append(r)
        print(f"\n== by {label}")
        for k, g in sorted(groups.items(), key=lambda kv: -len(kv[1])):
            if len(g) < 4:
                continue
            print(f"  {str(k):28s} n={len(g):4d}  laughs/msg {statistics.mean(x['laugh'] for x in g):.2f}  lift {statistics.mean(x['lift'] for x in g):+.2f}  "
                  f"% with a laugh {100 * sum(1 for x in g if x['laugh']) // len(g):3d}%  direct {100 * sum(1 for x in g if x['direct']) // len(g):3d}%  author replies {100 * sum(1 for x in g if x['author']) // len(g):3d}%")

    print(f"{len(rows)} posted transforms correlated to chat; baseline laugh messages per {a.window}s window: "
          + ", ".join(f"{c}={v:.2f}" for c, v in base_rate.items()))
    agg("era (gemini vs gemma)", lambda r: r["era"])
    agg("bot", lambda r: r["bot"])
    agg("theme word", lambda r: r["word"])
    agg("whole-word vs word-part swap", lambda r: "word-part" if r["partial"] else "whole word")
    agg("true replace vs added/other", lambda r: "true replace" if r["pure"] else "not a pure replace")
    agg("swap lands in last two words", lambda r: "end" if r["at_end"] else "earlier")
    agg("replaced a stopword/function word", lambda r: "stopword" if r["stopword"] else "content word")
    agg("message length", lambda r: "<=4 words" if r["n_tokens"] <= 4 else "5-8 words" if r["n_tokens"] <= 8 else "9+ words")
    agg("chat busyness", lambda r: "quiet (<6 msgs)" if r["msgs"] < 6 else "normal (6-14)" if r["msgs"] < 15 else "busy (15+)")

    def score(r):
        return 3 * r["yes"] - 3 * r["no"] + r["lift"]

    def show(title, rs):
        print(f"\n== {title}")
        for r in rs:
            print(f"  [{r['bot'][:8]:8s} {r['word']:6s} {r['era'][:2]}] laughs {r['before']}->{r['laugh']} yes{r['yes']} no{r['no']} | {r['original']!r}  =>  {r['posted']!r}")
            for d in r["directs"][:2]:
                print(f"        chat: {d[:90]!r}")

    ranked = sorted(rows, key=score)
    show("TOP 18 by explicit votes then laugh lift", ranked[::-1][:18])
    show("BOTTOM 18", ranked[:18])
    print("\nexplicit votes overall: yes", sum(r["yes"] for r in rows), "no/why/boo", sum(r["no"] for r in rows))

    out = Path(mc.ROOT) / "data" / "log-review"
    out.mkdir(parents=True, exist_ok=True)
    json.dump(rows, open(out / "review.json", "w", encoding="utf-8"), indent=1)
    print("\nwrote", out / "review.json")


if __name__ == "__main__":
    main()

# Backlog

Ideas that are agreed in principle but not scheduled. Newest first.

## Generalized bot pools

Today there is one optional `mainline_pool` (shares the mainline timer only), while callout
rotation, recent-target protection and `CommonConfig` are all global. The general model:

- **Pools of one or more bots**, each with its own members and overrides.
- Config resolved in three layers: **global common config -> pool common config -> per-bot config**.
- Per-pool: mainline timer, callout window/rotation, and possibly word lists and
  recent-target limit.

Why it is parked: it needs a `pools:` schema, three-layer resolution, and a pool panel in the
GUI. Worth doing once a second pool, or per-pool word lists, are actually wanted.

Open question when picked up: which bots rotate callouts together (all bots today, including
those outside `mainline_pool`).

## Known quirks

- A bot's hello + "turkey" easter egg (e.g. blampbot's) can never fire: the callout branch runs
  first and "turkey" is a shared callout word.

## Prompt and pipeline ideas that need app code

Found by the log review (`scripts/log_review.py`) and trialled in the prompt lab (`scripts/prompt_lab.py`);
the config-only ideas are just prompt text and live in the lab's `targeting` variants.

- **Several model calls per message.** Generate a few candidates and pick one with a rule-based
  reranker (concrete noun near the end, plural kept, not filler, not name-like). Caveat: the reranker's
  rules score the best- and worst-reacted real posts identically (0.58 vs 0.58), so it is unproven until
  there is more reaction data. A model judging its own edits is not an alternative: it rejected only 4 of 14 bad ones.
- **Log mod deletions** (Twitch message-delete events) so quality can be scored against real removals
  instead of the invented risky-line proxy.
- **Keep gemma-era data** for a week or two, then rerun `log_review.py --gemma-since ...` to compare the
  local model with Gemini on what chat actually reacted to.

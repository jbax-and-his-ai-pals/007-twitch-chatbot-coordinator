# blamputts - Twitch chatbot coordinator

One Kotlin process that runs several Twitch bot accounts side by side on a single shared
chat pipeline, with a web control panel for tuning them live. Built on
[Twitch4J](https://github.com/twitch4j/twitch4j) and [Ktor](https://ktor.io).

Every bot is defined entirely in `config.yaml` - there is no per-bot code. The bots play
word-swap games with chat (an LLM replaces the funniest word or word part in a chat line
with the bot's signature word) and answer a handful of addressed triggers.

## Features

- **Multiple bot identities, one pipeline.** Every bot evaluates each incoming message
  independently, then a scheduler arbitrates so **at most one bot acts per message**.
  Deliberate, addressed triggers always outrank the incidental word-swap.
- **Mainline transform.** The ambient behavior: after a cooldown and a minimum number of
  messages, a bot rewrites an eligible chat line via the LLM. Output is validated before it
  is sent (must actually contain the word, must stay similar to the original, no emoji, no
  forbidden words, casing restored deterministically when the model drops it).
- **Addressed triggers.** `hello`, `yes` / `no` / `why` verdicts, `wisdom`, `request`,
  `retry`, callout words, and per-bot easter eggs - all configurable.
- **Shared mainline timer (`mainline_pool`).** Bots listed in the pool take turns on one
  common timer instead of each running their own.
- **Shared callouts.** Callout words (e.g. `TURKEY`) are answered at most once per
  `callout_ms` window across *all* bots, with the turn rotating round-robin between them.
- **Recent-target protection.** Every bot shares a short memory of the last few chatters
  that any bot transformed, and skips them until they age out
  (`recent_mainline_target_limit`, default 3; the channel owner is exempt).
- **Web control panel.** Live activity feed, per-bot cooldown bars, per-bot and shared
  config editing (autosaved), chat simulation, and a fallback-mode switch that swaps the model
  for built-in canned replies if the local model can't load. GUI tuning persists in `data/state.json`.
- **Local models.** Runs on free, local models served by [Ollama](https://ollama.com)
  (default: `gemma4:12b`), switchable from a dropdown in the panel. The billed Vertex AI/Gemini
  client still exists but is strictly opt-in - nothing falls back to it.
- **Offline analysis tools**, both reading the persisted activity logs and never touching
  live bot state:
  - `/quality.html` - mines chat reactions to rank the best and worst bot responses.
  - `/replay.html` - replays real past prompts against whichever model is selected, several
    times each with per-call timing, to compare local models against what actually shipped.
    Nothing it does is ever sent to Twitch.

## Requirements

- JDK 17
- A Twitch application (client id/secret) and an IRC OAuth token for each bot account
- [Ollama](https://ollama.com) running locally with a model pulled (`ollama pull gemma4:12b`,
  about 8 GB, fits a 16 GB GPU)
- Optional: Google Cloud credentials for Vertex AI
  ([Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials)),
  only if you add a `vertex` model to `config.yaml`

## Setup

1. **Create your config.** Copy the template - the real file is gitignored because it
   holds secrets:

   ```bash
   cp src/main/resources/config.yaml.example src/main/resources/config.yaml
   ```

   The file is read from the classpath, so it must live in `src/main/resources/` and edits
   take effect on the next run/build. Fill in `channels`, each bot's `api` and
   `credentials`, and adjust prompts, words and cooldowns to taste. The template is
   heavily commented.

2. **Mint a bot account's IRC token.** Register a Twitch application while logged in as the
   bot account, then:

   ```bash
   ./gradlew setupBotAccount -PclientId=YOUR_CLIENT_ID -PclientSecret=YOUR_CLIENT_SECRET
   ```

   It walks the OAuth flow and prints an `oauth:...` token to paste into that bot's
   `credentials.irc`. It never edits `config.yaml` itself.

3. **Pull the local model.** `ollama pull gemma4:12b`, and keep Ollama running. (Only if you
   opt into Vertex: its project, region and model are hardcoded in `core/AIModel.kt`.)

## Running

```bash
./gradlew run
```

Then open `http://localhost:8080` (or whatever `gui_port` is set to). If the local model can't
load, flip the **fallback mode** switch: the bots answer with built-in canned replies instead of
going quiet (editable under "Fallback response overrides"). Live calls are free either way.

Tuning made in the panel is saved to `data/state.json` and wins over `config.yaml` on the
next start. Delete that file to fall back to the config file's values.

### Choosing a model

Models are declared under `models:` in `config.yaml` (see the template); pick one from the
dropdown in the panel's header (session-only; restarts return to `models.default`).
`gemma4:12b` was chosen by replaying real logged chats through seven local models: it passed
production's transform checks 95% of the time against 90% for the logged
`gemini-2.5-flash-lite` responses. To evaluate other models the same way:

```bash
python scripts/model_comparison.py sample
python scripts/model_comparison.py run gemma4:12b qwen3.5:9b
python scripts/model_comparison.py report   # -> data/model-comparison/report.html (local only)
```

The script only ever talks to local Ollama. `/replay.html` does a live single-model version of
the same replay inside the app.

Two more offline tools work on the logs (output goes to gitignored `data/`, since it holds real chat):

- `scripts/prompt_lab.py` replays real and invented lines through alternative transform prompts and
  multi-call pipelines on a local model, scoring ship rate, true find-and-replace, abstaining on risky
  lines, and time per usable message.
- The shipped transform prompt is the lab's `replace+examples` variant (find-and-replace rules plus worked examples), and a
  deterministic sincere-line gate skips sad or heavy messages before any model call. The previous prompts are saved in
  `docs/legacy-prompts.md`; set `transform.sincere_gate: false` to turn the gate off. toikeybot's `transform.sound_swap: true` makes a one-word ur/er/ir/or to oi edit (word to woid) a valid transform even without the theme word, and `sound_swap_chance` (default 0.5) is how often the code makes that edit itself by rule, with no model call (it skips names, mentions, links, short and filler words, and silent-e words like "sure"); otherwise the model picks a theme-word swap.
- `scripts/log_review.py` correlates every posted transform with how chat reacted afterwards.

## Tests

```bash
./gradlew test
```

## Project layout

| Path | What lives there |
|---|---|
| `controller/` | `Controller` (wiring), `AdminController` (network-free facade for the GUI), `Scheduler`, `Launcher` |
| `core/` | Config schema, cooldowns, `MessageDispatcher`, `MainlinePool`, `RecentMainlineTargets`, activity bus/log, LLM clients and model registry, quality and replay analysis |
| `core/engine/` | `ConfiguredPersonality` (the one personality implementation), prompt building, transform validation, casing restoration |
| `gui/` | Ktor routes, DTOs, and WebSocket activity feed |
| `tools/` | `setupBotAccount` OAuth helper |
| `resources/static/` | Plain HTML/JS/CSS for the control panel (no build step) |

## How a message flows

1. Twitch4J delivers a chat line; `MessageDispatcher` computes shared facts about it once.
2. Each enabled bot decides an intent: nothing, a secondary (addressed) action, or the
   mainline transform.
3. Secondary candidates compete first; mainline candidates are considered only when nobody
   wants a secondary action. `Scheduler` picks a single winner.
4. The winner acts, and every diagnostic from the whole pass lands in the activity feed as
   one entry (identical repeated lines collapsed).

## Credits

The retro control-panel themes use the IBM VGA 8x16 pixel font from The Ultimate Oldschool PC Font Pack by VileR (<https://int10h.org/oldschool-pc-fonts/>), licensed CC BY-SA 4.0 - see `src/main/resources/static/fonts/README.md`.

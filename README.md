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
- **Recent-target protection.** Every bot shares a short memory of the last few chatters
  that any bot transformed, and skips them until they age out
  (`recent_mainline_target_limit`, default 3; the channel owner is exempt).
- **Web control panel.** Live activity feed, per-bot cooldown bars, per-bot and shared
  config editing (autosaved), chat simulation, and a mock-mode switch that swaps the real
  LLM for a free canned one. GUI tuning persists in `data/state.json`.
- **Pluggable models.** Gemini on Vertex AI by default, plus optional local models served
  by [Ollama](https://ollama.com), switchable from a dropdown in the panel.
- **Offline analysis tools**, both reading the persisted activity logs and never touching
  live bot state:
  - `/quality.html` - mines chat reactions to rank the best and worst bot responses.
  - `/replay.html` - replays real past prompts against whichever model is selected, several
    times each with per-call timing, to compare local models against what actually shipped.
    Nothing it does is ever sent to Twitch.

## Requirements

- JDK 17
- A Twitch application (client id/secret) and an IRC OAuth token for each bot account
- For real LLM calls: Google Cloud credentials that Vertex AI can pick up
  ([Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials))
- Optional: Ollama running locally, for local models

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

3. **Point at your Vertex AI project.** The project, region and model name are currently
   hardcoded in `core/AIModel.kt`; change them there for your own setup.

## Running

```bash
./gradlew run
```

Then open `http://localhost:8080` (or whatever `gui_port` is set to). Use the **mock mode**
switch while experimenting so nothing is billed.

Tuning made in the panel is saved to `data/state.json` and wins over `config.yaml` on the
next start. Delete that file to fall back to the config file's values.

### Local models (optional)

```bash
ollama pull gemma3:4b
```

Declare it under `models:` in `config.yaml` (see the template), restart, and pick it from
the model dropdown in the panel's header. Then open `/replay.html` to compare it against
real past transforms.

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

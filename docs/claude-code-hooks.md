# Claude Code hooks for Svod

Two ready-made [Claude Code hook](https://docs.claude.com/en/docs/claude-code/hooks) scripts live in
[`dist/hooks/`](../dist/hooks/). They talk to the engine's App API only, through routes that already
exist. No engine or contract change is needed.

| Script | Hook events | App API |
|---|---|---|
| `svod-session-start.sh` | `SessionStart` | `GET /api/v1/memory/rulebook`, `GET /api/v1/changes` |
| `svod-capture.sh` | `Stop`, `PreCompact`, `SessionEnd` | `POST /api/v1/memory/capture` |

## What it does

- **SessionStart.** The script prints a short markdown block, which Claude Code adds to the session
  context. The block has two parts:
  - **The rule book**: your active policies and preferences, one line each (type, title, path,
    summary), plus how many memories wait in the review queue. The model opens a note with the MCP
    `read` tool when it needs the full text.
  - **The vault changes since your last session**: each commit's message, author or agent, and its
    first paths with their kind (`add`, `modify`, `delete`, `rename`, `restore`). The script saves
    the response's `head` and passes it as `since` next time. The first session has no saved head,
    so it shows the 20 newest commits.
- **Stop / PreCompact / SessionEnd.** The script posts the session transcript to the capture store
  (`messy/sessions/` in the vault).

How this differs from claude-mem and similar memory plugins:

- **Git history.** Every capture is a commit in the vault, so you can diff, blame and revert it like
  any other note.
- **Grow-only, quarantined capture.** The engine keeps one note per session. A larger transcript
  rewrites it, and an equal or smaller one changes nothing, so a stored session never shrinks.
  Captured transcripts are always left out of recall (search, `context_pack`, `grep`).
- **Review before recall.** Captures are raw material. A memory only reaches the rule book after it
  is distilled into a typed note and confirmed in the review queue
  (`/api/v1/memory/review`). The hooks never write to the rule book themselves.

## Requirements

- `bash`, `curl` and `jq` on the `PATH`. If `curl` or `jq` is missing, the scripts exit quietly.
- A running Svod engine with the App API on (default `http://127.0.0.1:7517`).

## Auth

- **Local engine (default).** With `localAdmin` on (the default for a loopback engine), keyless
  loopback calls are allowed. Leave `SVOD_API_KEY` unset.
- **Shared or central engine (`localAdmin: false`).** Set `SVOD_API_KEY` to your **personal App API
  key**, the one behind your `users[].keyRef` entry. This is not an MCP agent token from `agents[]`:
  the App API does not accept those. The rule book and the change feed need read access to the vault.
  Capture needs write access, so a reader's `svod-capture.sh` gets refused and records nothing.

## Setup

1. Copy `dist/hooks/svod-session-start.sh` and `dist/hooks/svod-capture.sh` somewhere stable, for
   example `~/.claude/hooks/`. The scripts don't have to be executable, because they are called
   through `bash`.
2. Add this to `~/.claude/settings.json` (or a project's `.claude/settings.json`), with the paths
   changed to where you put the scripts. Merge it with any `hooks` you already have.

```json
{
    "hooks": {
        "SessionStart": [
            { "hooks": [ { "type": "command", "command": "bash /path/to/svod-session-start.sh" } ] }
        ],
        "Stop": [
            { "hooks": [ { "type": "command", "command": "bash /path/to/svod-capture.sh" } ] }
        ],
        "PreCompact": [
            { "hooks": [ { "type": "command", "command": "bash /path/to/svod-capture.sh" } ] }
        ],
        "SessionEnd": [
            { "hooks": [ { "type": "command", "command": "bash /path/to/svod-capture.sh" } ] }
        ]
    }
}
```

3. Set any environment variables you need (below), for example in the `env` block of the same
   `settings.json`.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `SVOD_API_URL` | from the discovery file, else `http://127.0.0.1:7517` | App API base URL. Use `https://…` for a remote engine. |
| `SVOD_DISCOVERY_FILE` | `~/.config/svod/engine.json` | The file a running engine writes; its `host` and `appApiPort` are used when `SVOD_API_URL` is unset. The URL built from it is always `http://`, so an engine with `appApiTls` needs `SVOD_API_URL`. |
| `SVOD_API_KEY` | unset (keyless loopback) | Personal App API key, sent as `Authorization: Bearer …`. |
| `SVOD_VAULT` | unset (the default vault) | Vault id on a multi-vault engine, sent as `?vault=`. |

## State files

- `${XDG_STATE_HOME:-~/.local/state}/svod/hooks/last-head-<vault or default>`: the `head` commit from
  the last `SessionStart`. Delete it to see the 20 newest commits again.
- `${TMPDIR:-/tmp}/svod-capture/<session_id>`: the transcript size (UTF-8 bytes) the engine last
  accepted for that session. It is written only when the POST succeeds (`curl -f`).

## When the capture posts

- `PreCompact` and `SessionEnd` always post.
- `Stop` fires after every response. It posts only on the first capture of a session, or once the
  transcript is at least **twice** the size last accepted. A long session then makes a handful of
  commits instead of one per response.
- The body is built by `jq` straight from the transcript JSONL file, so the transcript never passes
  through the command line. It has `sessionId`, `project` (the basename of `cwd`), `transcript` and
  `startedAt` / `endedAt` (epoch millis from the entries' UTC timestamps; the current time if none
  parse).
- `transcript` is a **compacted** rendering, not the raw JSONL. Only user and assistant turns are
  kept, each under a `### user` / `### assistant` heading. Text blocks stay whole. A tool call keeps
  its name and the first 300 characters of its input, and a tool result keeps its first
  500 characters. Thinking blocks, images, meta and system entries, subagent sidechains and
  `isCompactSummary` entries (they restate earlier turns) are dropped. Most of a raw Claude Code
  transcript is tool output, so a capture should be a small fraction of the JSONL file size. The
  exact ratio depends on the session: one that is mostly long prose stays larger.
- The engine answers with `deduped: true` when it already holds an equal or larger transcript for
  the session. That counts as accepted.

## Verify

1. Start a Claude Code session. Its first context should show `## Svod rule book (N awaiting review)`.
   You can also run `echo '{}' | bash /path/to/svod-session-start.sh` in a terminal.
2. Ask something, wait for the answer, then look under `messy/sessions/` in the vault (or call
   `GET /api/v1/memory/sessions`). A note with `type: session` and your session id should be there.

## Known limits

The behaviour below is how the scripts are written to work. The test suite checks the routes and
fields they use against the engine, but does not run the scripts, so treat these as expectations
and use the steps under [Verify](#verify) on your own machine.

- **Best-effort.** Every exit in the scripts is `exit 0`, so a hook should never block Claude Code.
  If the engine is down, no size is recorded and the next event tries again. A crash or killed
  terminal (no `SessionEnd`) should lose at most the part since the last doubling.
- **A half-written last JSONL line**, or a lone surrogate escape such as `\uD800`, makes `jq` fail,
  so that event posts nothing. The next event tries again.
- **Two first captures** of one session that land in different seconds can create two session
  notes, because the note path carries `endedAt`.
- **Captured transcripts never appear in recall.** That is on purpose: they can hold secrets, and
  the write-path secret scanner is skipped for them. Distill them into typed notes to make them
  findable.
- The scripts are bash only. There is no Windows/PowerShell variant yet.

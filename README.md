# logseq-db-sync

Syncs pi session history into a Logseq DB graph through [`nbb-logseq`](https://github.com/logseq/logseq/tree/master/deps/db).

Behavior:
- Creates one Logseq page per pi conversation/session.
- Adds/updates message blocks on that conversation page.
- Adds a `[[Conversation Page]]` link block to today's journal page.
- Saves memory blocks directly to today's journal page.
- Captures long-term memory automatically with low-cost heuristics.
- Injects stored memory into context on every turn (including brand-new sessions).

## Setup

1. Install script dependencies:

```bash
cd packages/coding-agent/examples/extensions/logseq-db-sync/scripts
npm install
```

2. Start pi with the extension:

```bash
pi \
  --extension packages/coding-agent/examples/extensions/logseq-db-sync/index.ts \
  --logseq-graph "/absolute/path/to/logseq-graph"
```

Optional flags:
- `--logseq-nbb-root "~/Codes/projects/nbb-logseq"`: Override local `nbb-logseq` path.

Memory flags:
- `--logseq-memory-auto true|false`: Enable automatic memory capture (default: `true`).
- `--logseq-memory-scope global|project`: Memory scope (default: `project`).
- `--logseq-memory-max-items <n>`: Max memory items injected (default: `12`).
- `--logseq-memory-max-chars <n>`: Max characters injected (default: `6000`).
- `--logseq-memory-dedup-threshold <0..1>`: Near-duplicate threshold (default: `0.9`).
- `--logseq-memory-verify-model <provider/model>`: Optional cheap verifier model (default: disabled).
- `--logseq-memory-verify-threshold <0..1>`: Confidence threshold for rule-only acceptance (default: `0.7`).
- `--logseq-memory-verify-max-per-turn <n>`: Verifier budget per turn (default: `2`).
- `--logseq-memory-verify-max-per-hour <n>`: Verifier budget per hour (default: `30`).

## Commands

Conversation sync:
- `/logseq-sync`: Manual conversation sync.
- `/logseq-sync-status`: Show extension configuration and dependency status.

Memory:
- `/memory`: Show currently loaded Logseq memory records.
- `/memory-sync`: Force reload memory cache from Logseq.
- `/memory-on`: Enable automatic memory capture for current run.
- `/memory-off`: Disable automatic memory capture for current run.
- `/remember <text>`: Add explicit memory immediately.
- `/forget <id-or-text>`: Soft-delete memory entry by id or text snippet.

## Notes

- Conversation sync and memory storage use the same script entrypoint (`sync-logseq-db.cljs`) with different actions.
- Memory injection is transient (`context` hook), so it does not bloat session history.
- With verifier model disabled, the extension runs fully model-free for minimum cost.

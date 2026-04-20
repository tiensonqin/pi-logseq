# logseq-db-sync

Syncs pi session history into a Logseq DB graph through a single root `index.js` extension bundle.

Behavior:
- Creates one Logseq page per pi conversation/session.
- Adds/updates message blocks on that conversation page.
- Adds a `[[Conversation Page]]` link block to today's journal page.
- Saves memory blocks directly to today's journal page.
- Persists tasks as `#Task` blocks in Logseq journals.
- Stores task status using Logseq native status values (`Todo`, `Doing`, `Done`).
- Captures long-term memory automatically with low-cost heuristics.
- Injects stored memory into context on every turn (including brand-new sessions).

## Setup

1. Build the extension bundle:

```bash
npm install
npm run build
```

`shadow-cljs` is build-time only. Runtime execution only needs the generated root `index.js`.

2. Start pi with the extension bundle:

```bash
pi \
  --extension /absolute/path/to/index.js \
  --logseq-graph "/absolute/path/to/logseq-graph"
```

By default, the extension uses `~/logseq/graphs/pi-memory/sqlite.db`. Pass `--logseq-graph` to override it.

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
- `/logseq-sync-status`: Show extension configuration and memory runtime status.

Memory:
- `/memory`: Show currently loaded Logseq memory records.
- `/memory-sync`: Force reload memory cache from Logseq.
- `/memory-on`: Enable automatic memory capture for current run.
- `/memory-off`: Disable automatic memory capture for current run.
- `/remember <text>`: Add explicit memory immediately.
- `/forget <id-or-text>`: Soft-delete memory entry by id or text snippet.

Task tools:
- `TaskCreate`: Create a task in Logseq tagged as `#Task`.
- `TaskList`: List all persisted tasks.
- `TaskGet`: Get one task by id.
- `TaskUpdate`: Update task status/subject.
- Status mapping: `pending -> Todo`, `in_progress -> Doing`, `completed -> Done`.

## Notes

- All extension logic is implemented in ClojureScript and compiled into the root `index.js`.
- Runtime does not invoke `shadow-cljs` or any subprocess build step.
- Memory injection is transient (`context` hook), so it does not bloat session history.
- With verifier model disabled, the extension runs fully model-free for minimum cost.

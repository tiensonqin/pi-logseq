# logseq-db-sync

Syncs pi session history into a Logseq DB graph through [`nbb-logseq`](https://github.com/logseq/logseq/tree/master/deps/db).

Behavior:
- Creates one Logseq page per pi conversation/session.
- Adds/updates message blocks on that conversation page.
- Adds a `[[Conversation Page]]` link block to today's journal page.
- Creates today's journal page automatically if it does not exist.

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

Commands:
- `/logseq-sync`: Manual sync.
- `/logseq-sync-status`: Show extension configuration and dependency status.

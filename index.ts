/**
 * Logseq DB Sync Extension
 *
 * Persists pi sessions into a Logseq DB graph using nbb-logseq scripts.
 *
 * Behavior:
 * - One Logseq page per pi session
 * - Session page linked from today's journal page (journal page created if needed)
 * - Conversation and memory summaries (branch/compaction) synced as blocks
 */

import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { ExtensionAPI, ExtensionContext } from "@mariozechner/pi-coding-agent";
import {
	buildConversationPageTitle,
	computeJournalIntDate,
	extractTextFromSessionEntry,
	type LogseqSyncRecord,
} from "./lib.js";

const DEFAULT_NBB_LOGSEQ_ROOT = "~/Codes/projects/nbb-logseq";

interface SyncPayload {
	graphPath: string;
	conversationPageTitle: string;
	sessionId: string;
	sessionFile?: string;
	journalIntDate: number;
	records: LogseqSyncRecord[];
}

function expandHome(path: string): string {
	if (path === "~") return process.env.HOME || path;
	if (path.startsWith("~/")) {
		const home = process.env.HOME;
		return home ? join(home, path.slice(2)) : path;
	}
	return path;
}

function collectSyncRecords(ctx: ExtensionContext): LogseqSyncRecord[] {
	const records = ctx.sessionManager
		.getBranch()
		.map((entry) => extractTextFromSessionEntry(entry))
		.filter((record): record is LogseqSyncRecord => record !== undefined)
		.sort((a, b) => a.timestamp - b.timestamp);

	// Preserve first-seen ordering while removing duplicate ids.
	const deduped = new Map<string, LogseqSyncRecord>();
	for (const record of records) {
		if (!deduped.has(record.id)) {
			deduped.set(record.id, record);
		}
	}

	return Array.from(deduped.values());
}

function buildPayload(ctx: ExtensionContext, graphPath: string): SyncPayload {
	const header = ctx.sessionManager.getHeader();
	const sessionId = ctx.sessionManager.getSessionId();

	return {
		graphPath,
		conversationPageTitle: buildConversationPageTitle(sessionId, header?.timestamp),
		sessionId,
		sessionFile: ctx.sessionManager.getSessionFile(),
		journalIntDate: computeJournalIntDate(),
		records: collectSyncRecords(ctx),
	};
}

function writePayloadToTempFile(payload: SyncPayload): { tempDir: string; payloadPath: string } {
	const tempDir = mkdtempSync(join(tmpdir(), "pi-logseq-sync-"));
	const payloadPath = join(tempDir, "payload.json");
	writeFileSync(payloadPath, JSON.stringify(payload), "utf8");
	return { tempDir, payloadPath };
}

function parseSyncResult(stdout: string): { createdMessages?: number; linkedInJournal?: boolean } {
	const lastLine = stdout
		.split("\n")
		.map((line) => line.trim())
		.filter(Boolean)
		.pop();

	if (!lastLine) return {};

	try {
		const parsed = JSON.parse(lastLine) as {
			ok?: boolean;
			createdMessages?: number;
			linkedInJournal?: boolean;
		};
		if (parsed.ok) {
			return { createdMessages: parsed.createdMessages, linkedInJournal: parsed.linkedInJournal };
		}
	} catch {
		// Ignore parsing errors from noisy stdout and treat as unknown summary.
	}

	return {};
}

export default function logseqDbSyncExtension(pi: ExtensionAPI) {
	const extensionDir = dirname(fileURLToPath(import.meta.url));
	const scriptsDir = join(extensionDir, "scripts");
	const syncScriptPath = join(scriptsDir, "sync-logseq-db.cljs");

	pi.registerFlag("logseq-graph", {
		description: "Logseq DB graph name/path used by logseq-db-sync extension",
		type: "string",
	});

	pi.registerFlag("logseq-nbb-root", {
		description: "Path to local nbb-logseq project root",
		type: "string",
		default: DEFAULT_NBB_LOGSEQ_ROOT,
	});

	let syncQueue: Promise<void> = Promise.resolve();

	const enqueueSync = (ctx: ExtensionContext, reason: "session_start" | "agent_end" | "manual"): Promise<void> => {
		syncQueue = syncQueue
			.then(async () => {
				const graphPathFlag = pi.getFlag("logseq-graph");
				if (typeof graphPathFlag !== "string" || graphPathFlag.trim() === "") {
					if (reason === "manual" && ctx.hasUI) {
						ctx.ui.notify("Set --logseq-graph to enable Logseq DB sync", "warning");
					}
					return;
				}

				const nbbRootFlag = pi.getFlag("logseq-nbb-root");
				const nbbRoot =
					typeof nbbRootFlag === "string" && nbbRootFlag.trim() !== ""
						? expandHome(nbbRootFlag.trim())
						: expandHome(DEFAULT_NBB_LOGSEQ_ROOT);
				const nbbCliPath = join(nbbRoot, "cli.js");

				if (!existsSync(syncScriptPath)) {
					throw new Error(`Sync script not found: ${syncScriptPath}`);
				}
				if (!existsSync(nbbCliPath)) {
					throw new Error(`nbb-logseq CLI not found: ${nbbCliPath}`);
				}
				if (!existsSync(join(scriptsDir, "node_modules", "better-sqlite3"))) {
					throw new Error(`Missing scripts dependency. Run: cd ${scriptsDir} && npm install`);
				}

				const payload = buildPayload(ctx, graphPathFlag.trim());
				const { tempDir, payloadPath } = writePayloadToTempFile(payload);

				try {
					const result = await pi.exec("node", [nbbCliPath, syncScriptPath, payloadPath], {
						cwd: scriptsDir,
						timeout: 120000,
					});

					if (result.code !== 0) {
						const detail = result.stderr.trim() || result.stdout.trim() || "Unknown error";
						throw new Error(`Logseq DB sync failed (${reason}): ${detail}`);
					}

					if (reason === "manual" && ctx.hasUI) {
						const parsed = parseSyncResult(result.stdout);
						const messageCount = parsed.createdMessages ?? 0;
						const linkedSuffix = parsed.linkedInJournal ? ", linked in journal" : "";
						ctx.ui.notify(`Logseq sync complete: ${messageCount} new records${linkedSuffix}`, "info");
					}
				} finally {
					rmSync(tempDir, { recursive: true, force: true });
				}
			})
			.catch((error) => {
				const message = error instanceof Error ? error.message : String(error);
				if (ctx.hasUI) {
					ctx.ui.notify(message, "error");
				} else {
					process.stderr.write(`${message}\n`);
				}
			});

		return syncQueue;
	};

	pi.on("session_start", async (_event, ctx) => {
		void enqueueSync(ctx, "session_start");
	});

	pi.on("agent_end", async (_event, ctx) => {
		void enqueueSync(ctx, "agent_end");
	});

	pi.registerCommand("logseq-sync", {
		description: "Sync current session and memory to Logseq DB graph",
		handler: async (_args, ctx) => {
			await enqueueSync(ctx, "manual");
		},
	});

	pi.registerCommand("logseq-sync-status", {
		description: "Show logseq-db-sync extension config",
		handler: async (_args, ctx) => {
			const graph = pi.getFlag("logseq-graph");
			const nbbRoot = pi.getFlag("logseq-nbb-root");

			const lines = [
				`logseq-graph: ${typeof graph === "string" && graph.trim() !== "" ? graph : "(not set)"}`,
				`logseq-nbb-root: ${typeof nbbRoot === "string" && nbbRoot.trim() !== "" ? nbbRoot : DEFAULT_NBB_LOGSEQ_ROOT}`,
				`sync-script: ${syncScriptPath}`,
				`sync-script exists: ${existsSync(syncScriptPath)}`,
				`scripts dependencies installed: ${existsSync(join(scriptsDir, "node_modules", "better-sqlite3"))}`,
			];

			if (ctx.hasUI) {
				ctx.ui.notify(lines.join("\n"), "info");
			} else {
				process.stdout.write(`${lines.join("\n")}\n`);
			}
		},
	});
}

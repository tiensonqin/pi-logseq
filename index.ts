/**
 * Logseq DB Sync Extension
 *
 * Persists pi sessions into a Logseq DB graph and maintains low-cost automatic
 * long-term memory that is injected into context for new and existing sessions.
 */

import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { AgentMessage } from "@mariozechner/pi-agent-core";
import { type Api, complete, type Model, type TextContent } from "@mariozechner/pi-ai";
import type { ExtensionAPI, ExtensionContext } from "@mariozechner/pi-coding-agent";
import {
	buildConversationPageTitle,
	buildMemoryContextBlock,
	computeJournalIntDate,
	dedupeMemoryCandidates,
	deriveProjectKey,
	extractAutoMemoryCandidates,
	extractTextFromSessionEntry,
	hasStrongMemorySignal,
	type LogseqSyncRecord,
	type MemoryCandidate,
	type MemoryRecord,
	type MemoryScope,
	parseScope,
	toMemoryRecord,
} from "./lib.js";

const DEFAULT_NBB_LOGSEQ_ROOT = "~/Codes/projects/nbb-logseq";
const DEFAULT_MEMORY_SCOPE: MemoryScope = "project";
const DEFAULT_MEMORY_MAX_ITEMS = 12;
const DEFAULT_MEMORY_MAX_CHARS = 6000;
const DEFAULT_MEMORY_DEDUP_THRESHOLD = 0.9;
const DEFAULT_MEMORY_VERIFY_THRESHOLD = 0.7;
const DEFAULT_MEMORY_VERIFY_MAX_PER_TURN = 2;
const DEFAULT_MEMORY_VERIFY_MAX_PER_HOUR = 30;
const ONE_HOUR_MS = 60 * 60 * 1000;

interface SyncPayload {
	action: "syncConversation";
	graphPath: string;
	conversationPageTitle: string;
	sessionId: string;
	sessionFile?: string;
	journalIntDate: number;
	records: LogseqSyncRecord[];
}

interface QueryMemoryPayload {
	action: "queryMemory";
	graphPath: string;
	scope: MemoryScope;
	projectKey: string;
	limit: number;
}

interface UpsertMemoryPayload {
	action: "upsertMemory";
	graphPath: string;
	memoryRecords: MemoryRecord[];
}

interface ForgetMemoryPayload {
	action: "forgetMemory";
	graphPath: string;
	memoryId: string;
}

type ScriptPayload = SyncPayload | QueryMemoryPayload | UpsertMemoryPayload | ForgetMemoryPayload;

interface ParsedSyncResult {
	createdMessages?: number;
	linkedInJournal?: boolean;
}

interface QueryMemoryResult {
	ok: boolean;
	memories?: MemoryRecord[];
}

interface UpsertMemoryResult {
	ok: boolean;
	createdMemories?: number;
}

interface ForgetMemoryResult {
	ok: boolean;
	removed?: boolean;
}

interface MemoryConfig {
	autoEnabled: boolean;
	scope: MemoryScope;
	maxItems: number;
	maxChars: number;
	dedupThreshold: number;
	verifyModel?: string;
	verifyThreshold: number;
	verifyMaxPerTurn: number;
	verifyMaxPerHour: number;
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

	const deduped = new Map<string, LogseqSyncRecord>();
	for (const record of records) {
		if (!deduped.has(record.id)) {
			deduped.set(record.id, record);
		}
	}

	return Array.from(deduped.values());
}

function buildSyncPayload(ctx: ExtensionContext, graphPath: string): SyncPayload {
	const header = ctx.sessionManager.getHeader();
	const sessionId = ctx.sessionManager.getSessionId();
	const records = collectSyncRecords(ctx);

	return {
		action: "syncConversation",
		graphPath,
		conversationPageTitle: buildConversationPageTitle(records, header?.timestamp),
		sessionId,
		sessionFile: ctx.sessionManager.getSessionFile(),
		journalIntDate: computeJournalIntDate(),
		records,
	};
}

function writePayloadToTempFile(payload: ScriptPayload): { tempDir: string; payloadPath: string } {
	const tempDir = mkdtempSync(join(tmpdir(), "pi-logseq-sync-"));
	const payloadPath = join(tempDir, "payload.json");
	writeFileSync(payloadPath, JSON.stringify(payload), "utf8");
	return { tempDir, payloadPath };
}

function parseLastJsonLine(stdout: string): Record<string, unknown> {
	const lastLine = stdout
		.split("\n")
		.map((line) => line.trim())
		.filter(Boolean)
		.pop();

	if (!lastLine) {
		return { ok: false, error: "Missing JSON output" };
	}

	try {
		const parsed = JSON.parse(lastLine) as Record<string, unknown>;
		return parsed;
	} catch {
		return { ok: false, error: "Failed to parse JSON output" };
	}
}

function parseNumberFlag(value: string | boolean | undefined, fallback: number): number {
	if (typeof value !== "string") {
		return fallback;
	}
	const parsed = Number(value);
	if (!Number.isFinite(parsed)) {
		return fallback;
	}
	return parsed;
}

function parseBooleanFlag(value: string | boolean | undefined, fallback: boolean): boolean {
	if (typeof value === "boolean") {
		return value;
	}
	if (typeof value === "string") {
		const normalized = value.trim().toLowerCase();
		if (normalized === "true") return true;
		if (normalized === "false") return false;
	}
	return fallback;
}

function getMemoryConfig(pi: ExtensionAPI): MemoryConfig {
	const scopeFlag = pi.getFlag("logseq-memory-scope");
	const verifyModelFlag = pi.getFlag("logseq-memory-verify-model");

	return {
		autoEnabled: parseBooleanFlag(pi.getFlag("logseq-memory-auto"), true),
		scope: parseScope(typeof scopeFlag === "string" ? scopeFlag : undefined),
		maxItems: Math.max(
			1,
			Math.floor(parseNumberFlag(pi.getFlag("logseq-memory-max-items"), DEFAULT_MEMORY_MAX_ITEMS)),
		),
		maxChars: Math.max(
			250,
			Math.floor(parseNumberFlag(pi.getFlag("logseq-memory-max-chars"), DEFAULT_MEMORY_MAX_CHARS)),
		),
		dedupThreshold: Math.min(
			0.99,
			Math.max(0.5, parseNumberFlag(pi.getFlag("logseq-memory-dedup-threshold"), DEFAULT_MEMORY_DEDUP_THRESHOLD)),
		),
		verifyModel:
			typeof verifyModelFlag === "string" && verifyModelFlag.trim() !== "" ? verifyModelFlag.trim() : undefined,
		verifyThreshold: Math.min(
			0.95,
			Math.max(0.35, parseNumberFlag(pi.getFlag("logseq-memory-verify-threshold"), DEFAULT_MEMORY_VERIFY_THRESHOLD)),
		),
		verifyMaxPerTurn: Math.max(
			0,
			Math.floor(
				parseNumberFlag(pi.getFlag("logseq-memory-verify-max-per-turn"), DEFAULT_MEMORY_VERIFY_MAX_PER_TURN),
			),
		),
		verifyMaxPerHour: Math.max(
			0,
			Math.floor(
				parseNumberFlag(pi.getFlag("logseq-memory-verify-max-per-hour"), DEFAULT_MEMORY_VERIFY_MAX_PER_HOUR),
			),
		),
	};
}

function getGraphPath(pi: ExtensionAPI): string | undefined {
	const graphPathFlag = pi.getFlag("logseq-graph");
	if (typeof graphPathFlag !== "string") {
		return undefined;
	}
	const trimmed = graphPathFlag.trim();
	return trimmed.length > 0 ? trimmed : undefined;
}

function getNbbCliPath(pi: ExtensionAPI): string {
	const nbbRootFlag = pi.getFlag("logseq-nbb-root");
	const nbbRoot =
		typeof nbbRootFlag === "string" && nbbRootFlag.trim() !== ""
			? expandHome(nbbRootFlag.trim())
			: expandHome(DEFAULT_NBB_LOGSEQ_ROOT);
	return join(nbbRoot, "cli.js");
}

function mergeMemoryRecords(existing: MemoryRecord[], incoming: MemoryRecord[]): MemoryRecord[] {
	const merged = new Map<string, MemoryRecord>();
	for (const record of existing) {
		if (!record.deleted) {
			merged.set(record.id, record);
		}
	}
	for (const record of incoming) {
		if (!record.deleted) {
			merged.set(record.id, record);
		}
	}
	return Array.from(merged.values());
}

function pickCandidateId(records: MemoryRecord[], input: string): string | undefined {
	const trimmed = input.trim();
	if (!trimmed) return undefined;

	const byId = records.find((record) => record.id === trimmed);
	if (byId) return byId.id;

	const lower = trimmed.toLowerCase();
	const byText = records.find((record) => record.text.toLowerCase().includes(lower));
	return byText?.id;
}

function extractJsonObject(text: string): string | undefined {
	const direct = text.trim();
	if (direct.startsWith("{") && direct.endsWith("}")) {
		return direct;
	}

	const match = text.match(/\{[\s\S]*\}/);
	return match?.[0];
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

	pi.registerFlag("logseq-memory-auto", {
		description: "Enable automatic memory capture to Logseq",
		type: "boolean",
		default: true,
	});

	pi.registerFlag("logseq-memory-scope", {
		description: "Memory scope (global|project)",
		type: "string",
		default: DEFAULT_MEMORY_SCOPE,
	});

	pi.registerFlag("logseq-memory-max-items", {
		description: "Maximum memory items injected per turn",
		type: "string",
		default: String(DEFAULT_MEMORY_MAX_ITEMS),
	});

	pi.registerFlag("logseq-memory-max-chars", {
		description: "Maximum memory characters injected per turn",
		type: "string",
		default: String(DEFAULT_MEMORY_MAX_CHARS),
	});

	pi.registerFlag("logseq-memory-dedup-threshold", {
		description: "Near-duplicate threshold for memory insertion",
		type: "string",
		default: String(DEFAULT_MEMORY_DEDUP_THRESHOLD),
	});

	pi.registerFlag("logseq-memory-verify-model", {
		description: "Optional verifier model in provider/model format",
		type: "string",
		default: "",
	});

	pi.registerFlag("logseq-memory-verify-threshold", {
		description: "Minimum confidence to accept candidate without verifier",
		type: "string",
		default: String(DEFAULT_MEMORY_VERIFY_THRESHOLD),
	});

	pi.registerFlag("logseq-memory-verify-max-per-turn", {
		description: "Maximum verifier calls per turn",
		type: "string",
		default: String(DEFAULT_MEMORY_VERIFY_MAX_PER_TURN),
	});

	pi.registerFlag("logseq-memory-verify-max-per-hour", {
		description: "Maximum verifier calls per hour",
		type: "string",
		default: String(DEFAULT_MEMORY_VERIFY_MAX_PER_HOUR),
	});

	let scriptQueue: Promise<unknown> = Promise.resolve();
	let memoryCache: MemoryRecord[] = [];
	let memoryCacheLoaded = false;
	let autoCaptureEnabled = parseBooleanFlag(pi.getFlag("logseq-memory-auto"), true);
	let verifyWindowStartedAt = Date.now();
	let verifyCallsInWindow = 0;

	const enqueueTask = <T>(task: () => Promise<T>): Promise<T> => {
		const run = scriptQueue.then(task, task);
		scriptQueue = run.then(
			() => undefined,
			() => undefined,
		);
		return run;
	};

	const runScript = async <T>(_ctx: ExtensionContext, payload: ScriptPayload): Promise<T> => {
		const nbbCliPath = getNbbCliPath(pi);
		if (!existsSync(syncScriptPath)) {
			throw new Error(`Sync script not found: ${syncScriptPath}`);
		}
		if (!existsSync(nbbCliPath)) {
			throw new Error(`nbb-logseq CLI not found: ${nbbCliPath}`);
		}
		if (!existsSync(join(scriptsDir, "node_modules", "better-sqlite3"))) {
			throw new Error(`Missing scripts dependency. Run: cd ${scriptsDir} && npm install`);
		}

		const { tempDir, payloadPath } = writePayloadToTempFile(payload);
		try {
			const result = await pi.exec("node", [nbbCliPath, syncScriptPath, payloadPath], {
				cwd: scriptsDir,
				timeout: 120000,
			});

			if (result.code !== 0) {
				const detail = result.stderr.trim() || result.stdout.trim() || "Unknown error";
				throw new Error(`Logseq DB action failed (${payload.action}): ${detail}`);
			}

			const parsed = parseLastJsonLine(result.stdout);
			if (parsed.ok !== true) {
				const detail = typeof parsed.error === "string" ? parsed.error : "Malformed script result";
				throw new Error(`Logseq DB action failed (${payload.action}): ${detail}`);
			}
			return parsed as T;
		} finally {
			rmSync(tempDir, { recursive: true, force: true });
		}
	};

	const syncConversation = async (
		ctx: ExtensionContext,
		reason: "session_start" | "agent_end" | "manual",
	): Promise<ParsedSyncResult | undefined> => {
		const graphPath = getGraphPath(pi);
		if (!graphPath) {
			if (reason === "manual" && ctx.hasUI) {
				ctx.ui.notify("Set --logseq-graph to enable Logseq DB sync", "warning");
			}
			return undefined;
		}

		const payload = buildSyncPayload(ctx, graphPath);
		return enqueueTask(async () => {
			const parsed = await runScript<ParsedSyncResult & { ok: boolean }>(ctx, payload);
			return parsed;
		});
	};

	const loadMemory = async (ctx: ExtensionContext): Promise<MemoryRecord[]> => {
		const graphPath = getGraphPath(pi);
		if (!graphPath) {
			memoryCache = [];
			memoryCacheLoaded = true;
			return [];
		}

		const config = getMemoryConfig(pi);
		const payload: QueryMemoryPayload = {
			action: "queryMemory",
			graphPath,
			scope: config.scope,
			projectKey: deriveProjectKey(ctx.cwd),
			limit: Math.max(config.maxItems * 4, 24),
		};

		const parsed = await enqueueTask(async () => runScript<QueryMemoryResult>(ctx, payload));
		const memories = Array.isArray(parsed.memories) ? parsed.memories.filter((record) => !record.deleted) : [];
		memoryCache = memories;
		memoryCacheLoaded = true;
		return memories;
	};

	const upsertMemory = async (ctx: ExtensionContext, records: MemoryRecord[]): Promise<number> => {
		if (records.length === 0) return 0;
		const graphPath = getGraphPath(pi);
		if (!graphPath) return 0;

		const payload: UpsertMemoryPayload = {
			action: "upsertMemory",
			graphPath,
			memoryRecords: records,
		};

		const parsed = await enqueueTask(async () => runScript<UpsertMemoryResult>(ctx, payload));
		memoryCache = mergeMemoryRecords(memoryCache, records);
		memoryCacheLoaded = true;
		return parsed.createdMemories ?? 0;
	};

	const forgetMemory = async (ctx: ExtensionContext, memoryId: string): Promise<boolean> => {
		const graphPath = getGraphPath(pi);
		if (!graphPath) return false;

		const payload: ForgetMemoryPayload = {
			action: "forgetMemory",
			graphPath,
			memoryId,
		};

		const parsed = await enqueueTask(async () => runScript<ForgetMemoryResult>(ctx, payload));
		if (parsed.removed) {
			memoryCache = memoryCache.filter((record) => record.id !== memoryId);
		}
		return parsed.removed === true;
	};

	const shouldVerify = (candidate: MemoryCandidate, config: MemoryConfig): boolean => {
		if (candidate.confidence >= 0.82) return false;
		if (candidate.confidence < 0.35) return false;
		if (candidate.confidence >= config.verifyThreshold && hasStrongMemorySignal(candidate.text)) {
			return false;
		}
		return Boolean(config.verifyModel);
	};

	const resolveVerifierModel = async (ctx: ExtensionContext, verifyModel: string): Promise<Model<Api> | undefined> => {
		const available = await ctx.modelRegistry.getAvailable();
		return available.find((model) => `${model.provider}/${model.id}` === verifyModel);
	};

	const verifyCandidate = async (
		ctx: ExtensionContext,
		candidate: MemoryCandidate,
		config: MemoryConfig,
		verifyCallsThisTurn: { count: number },
	): Promise<MemoryCandidate | undefined> => {
		if (!config.verifyModel || !shouldVerify(candidate, config)) {
			return candidate.confidence >= config.verifyThreshold ? candidate : undefined;
		}

		const now = Date.now();
		if (now - verifyWindowStartedAt > ONE_HOUR_MS) {
			verifyWindowStartedAt = now;
			verifyCallsInWindow = 0;
		}

		if (verifyCallsThisTurn.count >= config.verifyMaxPerTurn || verifyCallsInWindow >= config.verifyMaxPerHour) {
			return candidate.confidence >= config.verifyThreshold ? candidate : undefined;
		}

		const model = await resolveVerifierModel(ctx, config.verifyModel);
		if (!model) {
			return candidate.confidence >= config.verifyThreshold ? candidate : undefined;
		}

		const auth = await ctx.modelRegistry.getApiKeyAndHeaders(model);
		if (!auth.ok || !auth.apiKey) {
			return candidate.confidence >= config.verifyThreshold ? candidate : undefined;
		}

		verifyCallsThisTurn.count += 1;
		verifyCallsInWindow += 1;

		const prompt =
			`You validate if a sentence is durable long-term memory for a coding assistant.\n` +
			`Keep only stable preferences, constraints, conventions, or workflow rules.\n` +
			`Reject transient task status, temporary logs, timestamps, or one-off updates.\n\n` +
			`Return strict JSON only with keys: keep(boolean), rewrite(string), confidence(number 0..1).\n\n` +
			`Sentence: ${candidate.text}`;

		const response = await complete(
			model,
			{
				messages: [
					{
						role: "user",
						content: [{ type: "text", text: prompt }],
						timestamp: Date.now(),
					},
				],
			},
			{
				apiKey: auth.apiKey,
				headers: auth.headers,
			},
		);

		const responseText = response.content
			.filter((item): item is TextContent => item.type === "text")
			.map((item) => item.text)
			.join("\n");
		const jsonChunk = extractJsonObject(responseText);
		if (!jsonChunk) {
			return candidate.confidence >= config.verifyThreshold ? candidate : undefined;
		}

		try {
			const parsed = JSON.parse(jsonChunk) as {
				keep?: boolean;
				rewrite?: string;
				confidence?: number;
			};
			if (parsed.keep !== true) {
				return undefined;
			}

			const rewritten =
				typeof parsed.rewrite === "string" && parsed.rewrite.trim() !== "" ? parsed.rewrite.trim() : candidate.text;
			const verifierConfidence =
				typeof parsed.confidence === "number" && Number.isFinite(parsed.confidence)
					? Math.min(1, Math.max(0, parsed.confidence))
					: candidate.confidence;

			return {
				...candidate,
				text: rewritten,
				confidence: Math.max(candidate.confidence, verifierConfidence),
			};
		} catch {
			return candidate.confidence >= config.verifyThreshold ? candidate : undefined;
		}
	};

	const autoCaptureMemory = async (messages: AgentMessage[], ctx: ExtensionContext): Promise<void> => {
		if (!autoCaptureEnabled) return;
		if (!getGraphPath(pi)) return;

		const config = getMemoryConfig(pi);
		const extracted = extractAutoMemoryCandidates(messages, { scope: config.scope });
		if (extracted.length === 0) {
			return;
		}

		if (!memoryCacheLoaded) {
			await loadMemory(ctx);
		}

		const deduped = dedupeMemoryCandidates(extracted, memoryCache, config.dedupThreshold);
		if (deduped.length === 0) {
			return;
		}

		const verifyCallsThisTurn = { count: 0 };
		const accepted: MemoryCandidate[] = [];

		for (const candidate of deduped) {
			const verified = await verifyCandidate(ctx, candidate, config, verifyCallsThisTurn);
			if (!verified) continue;
			accepted.push(verified);
		}

		if (accepted.length === 0) {
			return;
		}

		const now = Date.now();
		const projectKey = deriveProjectKey(ctx.cwd);
		const sourceSessionId = ctx.sessionManager.getSessionId();
		const sourceTurnId = ctx.sessionManager.getLeafId() ?? undefined;

		const records = accepted.map((candidate) =>
			toMemoryRecord({
				candidate,
				projectKey,
				sourceSessionId,
				sourceTurnId,
				timestamp: now,
			}),
		);

		const created = await upsertMemory(ctx, records);
		if (created > 0 && ctx.hasUI) {
			ctx.ui.notify(`Logseq memory captured: ${created} item${created === 1 ? "" : "s"}`, "info");
		}
	};

	pi.on("session_start", async (_event, ctx) => {
		try {
			void syncConversation(ctx, "session_start");
			await loadMemory(ctx);
		} catch (error) {
			const message = error instanceof Error ? error.message : String(error);
			if (ctx.hasUI) {
				ctx.ui.notify(message, "error");
			} else {
				process.stderr.write(`${message}\n`);
			}
		}
	});

	pi.on("context", async (event, ctx) => {
		if (!getGraphPath(pi)) return;
		if (!memoryCacheLoaded) {
			await loadMemory(ctx);
		}

		const config = getMemoryConfig(pi);
		const block = buildMemoryContextBlock(memoryCache, config.maxItems, config.maxChars);
		if (!block) {
			return;
		}

		const filtered = event.messages.filter((message) => {
			if (message.role !== "custom") return true;
			return message.customType !== "logseq-memory-context";
		});

		const memoryMessage: AgentMessage = {
			role: "custom",
			customType: "logseq-memory-context",
			content: block,
			display: false,
			details: { count: memoryCache.length },
			timestamp: Date.now(),
		};

		return {
			messages: [...filtered, memoryMessage],
		};
	});

	pi.on("agent_end", async (event, ctx) => {
		try {
			void syncConversation(ctx, "agent_end");
			await autoCaptureMemory(event.messages, ctx);
		} catch (error) {
			const message = error instanceof Error ? error.message : String(error);
			if (ctx.hasUI) {
				ctx.ui.notify(message, "error");
			} else {
				process.stderr.write(`${message}\n`);
			}
		}
	});

	pi.registerCommand("logseq-sync", {
		description: "Sync current session and memory to Logseq DB graph",
		handler: async (_args, ctx) => {
			try {
				const parsed = await syncConversation(ctx, "manual");
				if (!parsed) return;
				const messageCount = parsed.createdMessages ?? 0;
				const linkedSuffix = parsed.linkedInJournal ? ", linked in journal" : "";
				if (ctx.hasUI) {
					ctx.ui.notify(`Logseq sync complete: ${messageCount} new records${linkedSuffix}`, "info");
				}
			} catch (error) {
				const message = error instanceof Error ? error.message : String(error);
				if (ctx.hasUI) {
					ctx.ui.notify(message, "error");
				} else {
					process.stderr.write(`${message}\n`);
				}
			}
		},
	});

	pi.registerCommand("logseq-sync-status", {
		description: "Show logseq-db-sync extension config",
		handler: async (_args, ctx) => {
			const graph = getGraphPath(pi);
			const nbbCli = getNbbCliPath(pi);
			const config = getMemoryConfig(pi);

			const lines = [
				`logseq-graph: ${graph ?? "(not set)"}`,
				`logseq-nbb-cli: ${nbbCli}`,
				`sync-script: ${syncScriptPath}`,
				`sync-script exists: ${existsSync(syncScriptPath)}`,
				`scripts dependencies installed: ${existsSync(join(scriptsDir, "node_modules", "better-sqlite3"))}`,
				`memory auto: ${autoCaptureEnabled}`,
				`memory scope: ${config.scope}`,
				`memory cache size: ${memoryCache.length}`,
				`memory verify model: ${config.verifyModel ?? "(disabled)"}`,
				`memory verify calls/hour: ${verifyCallsInWindow}/${config.verifyMaxPerHour}`,
			];

			if (ctx.hasUI) {
				ctx.ui.notify(lines.join("\n"), "info");
			} else {
				process.stdout.write(`${lines.join("\n")}\n`);
			}
		},
	});

	pi.registerCommand("memory", {
		description: "Show memory currently loaded from Logseq",
		handler: async (_args, ctx) => {
			if (!memoryCacheLoaded) {
				await loadMemory(ctx);
			}

			if (memoryCache.length === 0) {
				if (ctx.hasUI) {
					ctx.ui.notify("No Logseq memory loaded", "info");
				}
				return;
			}

			const preview = memoryCache.slice(0, 12).map((record) => `${record.id} [${record.scope}] ${record.text}`);
			const text = [`Loaded memory (${memoryCache.length}):`, ...preview].join("\n");
			if (ctx.hasUI) {
				ctx.ui.notify(text, "info");
			} else {
				process.stdout.write(`${text}\n`);
			}
		},
	});

	pi.registerCommand("memory-sync", {
		description: "Reload memory cache from Logseq",
		handler: async (_args, ctx) => {
			const loaded = await loadMemory(ctx);
			if (ctx.hasUI) {
				ctx.ui.notify(`Logseq memory cache refreshed (${loaded.length} items)`, "info");
			}
		},
	});

	pi.registerCommand("memory-on", {
		description: "Enable automatic memory capture",
		handler: async (_args, ctx) => {
			autoCaptureEnabled = true;
			if (ctx.hasUI) {
				ctx.ui.notify("Automatic Logseq memory capture enabled", "info");
			}
		},
	});

	pi.registerCommand("memory-off", {
		description: "Disable automatic memory capture",
		handler: async (_args, ctx) => {
			autoCaptureEnabled = false;
			if (ctx.hasUI) {
				ctx.ui.notify("Automatic Logseq memory capture disabled", "info");
			}
		},
	});

	pi.registerCommand("remember", {
		description: "Persist explicit memory in Logseq",
		handler: async (args, ctx) => {
			const text = args.trim();
			if (!text) {
				if (ctx.hasUI) {
					ctx.ui.notify("Usage: /remember <text>", "warning");
				}
				return;
			}

			const config = getMemoryConfig(pi);
			const now = Date.now();
			const candidate: MemoryCandidate = {
				text,
				normalizedText: text.toLowerCase().replace(/\s+/g, " ").trim(),
				scope: config.scope,
				type: "preference",
				confidence: 0.99,
			};
			const record = toMemoryRecord({
				candidate,
				projectKey: deriveProjectKey(ctx.cwd),
				sourceSessionId: ctx.sessionManager.getSessionId(),
				sourceTurnId: ctx.sessionManager.getLeafId() ?? undefined,
				timestamp: now,
			});

			const created = await upsertMemory(ctx, [record]);
			if (ctx.hasUI) {
				ctx.ui.notify(`Memory saved${created > 0 ? "" : " (already existed)"}`, "info");
			}
		},
	});

	pi.registerCommand("forget", {
		description: "Forget memory by id or text snippet",
		handler: async (args, ctx) => {
			if (!memoryCacheLoaded) {
				await loadMemory(ctx);
			}

			const memoryId = pickCandidateId(memoryCache, args);
			if (!memoryId) {
				if (ctx.hasUI) {
					ctx.ui.notify("No matching memory found", "warning");
				}
				return;
			}

			const removed = await forgetMemory(ctx, memoryId);
			if (ctx.hasUI) {
				ctx.ui.notify(
					removed ? `Forgot memory ${memoryId}` : "Memory not found in graph",
					removed ? "info" : "warning",
				);
			}
		},
	});
}

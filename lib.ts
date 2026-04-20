import type { AgentMessage } from "@mariozechner/pi-agent-core";
import { v5 as uuidv5 } from "uuid";
import type { SessionEntry } from "../../../src/core/session-manager.js";

export interface LogseqSyncRecord {
	id: string;
	role: string;
	text: string;
	timestamp: number;
}

export type MemoryScope = "global" | "project";

export type MemoryType = "preference" | "constraint" | "workflow" | "fact";

export interface MemoryCandidate {
	text: string;
	normalizedText: string;
	scope: MemoryScope;
	type: MemoryType;
	confidence: number;
}

export interface MemoryRecord {
	id: string;
	text: string;
	normalizedText: string;
	scope: MemoryScope;
	type: MemoryType;
	confidence: number;
	projectKey: string;
	sourceSessionId: string;
	sourceTurnId?: string;
	createdAt: number;
	updatedAt: number;
	deleted: boolean;
}

export interface ExtractMemoryOptions {
	scope: MemoryScope;
}

const MIN_SENTENCE_LENGTH = 12;
const MAX_SENTENCE_LENGTH = 220;

const STRONG_MEMORY_PATTERNS: RegExp[] = [
	/\bremember\b/i,
	/\bfrom now on\b/i,
	/\balways\b/i,
	/\bnever\b/i,
	/\bmust\b/i,
	/\bdo not\b/i,
	/\bdon't\b/i,
];

const MEMORY_PATTERNS: RegExp[] = [
	...STRONG_MEMORY_PATTERNS,
	/\bprefer\b/i,
	/\bplease use\b/i,
	/\bwe use\b/i,
	/\bwe do not\b/i,
	/\bwe don't\b/i,
	/\bconvention\b/i,
	/\bworkflow\b/i,
	/\bstyle\b/i,
	/\bpolicy\b/i,
];

const EPHEMERAL_PATTERNS: RegExp[] = [
	/\b(today|now|currently|just now)\b/i,
	/\b(done|finished|passed|failed|running|started)\b/i,
	/^\$/,
	/\bexit code\b/i,
	/\berror:\b/i,
];

function isValidDate(value: string | undefined): boolean {
	if (!value) return false;
	return !Number.isNaN(new Date(value).getTime());
}

function formatIsoDate(date: Date): string {
	const year = date.getFullYear();
	const month = String(date.getMonth() + 1).padStart(2, "0");
	const day = String(date.getDate()).padStart(2, "0");
	return `${year}-${month}-${day}`;
}

function formatHourMinute(date: Date): string {
	const hours = String(date.getHours()).padStart(2, "0");
	const minutes = String(date.getMinutes()).padStart(2, "0");
	return `${hours}:${minutes}`;
}

function summarizeConversation(records: LogseqSyncRecord[]): string {
	const preferred = records.find((record) => record.role === "user" && record.text.trim().length > 0) ?? records[0];
	const raw = preferred?.text ?? "Untitled";
	const compact = raw.replace(/\s+/g, " ").trim();
	const sentence = compact.split(/[.!?]/)[0]?.trim() || compact;
	const cleaned = sentence.replace(/[[\]`*_~]/g, "").trim();
	if (cleaned.length <= 72) return cleaned;
	return `${cleaned.slice(0, 69).trimEnd()}...`;
}

export function buildConversationPageTitle(records: LogseqSyncRecord[], sessionTimestamp: string | undefined): string {
	const parsedDate = isValidDate(sessionTimestamp) && sessionTimestamp ? new Date(sessionTimestamp) : new Date();
	const date = formatIsoDate(parsedDate);
	const time = formatHourMinute(parsedDate);
	const summary = summarizeConversation(records);
	return `Pi Conversation ${date} ${time} - ${summary}`;
}

export function computeJournalIntDate(date: Date = new Date()): number {
	const year = date.getFullYear();
	const month = String(date.getMonth() + 1).padStart(2, "0");
	const day = String(date.getDate()).padStart(2, "0");
	return Number.parseInt(`${year}${month}${day}`, 10);
}

function extractTextFromContent(content: unknown): string {
	if (typeof content === "string") {
		return content.trim();
	}

	if (!Array.isArray(content)) {
		return "";
	}

	const parts: string[] = [];
	for (const block of content) {
		if (!block || typeof block !== "object") continue;
		const candidate = block as {
			type?: string;
			text?: string;
			thinking?: string;
			name?: string;
			arguments?: unknown;
		};
		if (candidate.type === "text" && typeof candidate.text === "string") {
			parts.push(candidate.text);
		} else if (candidate.type === "thinking" && typeof candidate.thinking === "string") {
			parts.push(candidate.thinking);
		}
	}

	return parts.join("\n").trim();
}

function parseIsoTimestamp(timestamp: string): number {
	const value = new Date(timestamp).getTime();
	return Number.isNaN(value) ? Date.now() : value;
}

function extractMessageRecord(entry: Extract<SessionEntry, { type: "message" }>): LogseqSyncRecord | undefined {
	const { message } = entry;
	if (message.role === "toolResult") {
		return undefined;
	}

	let text = "";

	switch (message.role) {
		case "user":
		case "assistant":
		case "custom":
			text = extractTextFromContent(message.content);
			break;
		case "bashExecution":
			text = [`$ ${message.command}`, message.output].filter(Boolean).join("\n").trim();
			break;
		case "branchSummary":
		case "compactionSummary":
			text = message.summary.trim();
			break;
	}

	if (!text) return undefined;

	return {
		id: entry.id,
		role: message.role,
		text,
		timestamp: parseIsoTimestamp(entry.timestamp),
	};
}

export function extractTextFromSessionEntry(entry: SessionEntry): LogseqSyncRecord | undefined {
	if (entry.type === "message") {
		return extractMessageRecord(entry);
	}

	if (entry.type === "branch_summary" && entry.summary.trim()) {
		return {
			id: `branch:${entry.id}`,
			role: "branchSummary",
			text: entry.summary.trim(),
			timestamp: parseIsoTimestamp(entry.timestamp),
		};
	}

	if (entry.type === "compaction" && entry.summary.trim()) {
		return {
			id: `compaction:${entry.id}`,
			role: "compactionSummary",
			text: entry.summary.trim(),
			timestamp: parseIsoTimestamp(entry.timestamp),
		};
	}

	return undefined;
}

function splitIntoSentences(text: string): string[] {
	return text
		.split(/\n+/)
		.flatMap((line) => line.split(/[.!?]+/))
		.map((line) => line.trim())
		.filter((line) => line.length >= MIN_SENTENCE_LENGTH && line.length <= MAX_SENTENCE_LENGTH);
}

function isLikelyEphemeral(text: string): boolean {
	if (text.length < MIN_SENTENCE_LENGTH || text.length > MAX_SENTENCE_LENGTH) {
		return true;
	}

	if (/\d{6,}/.test(text)) {
		return true;
	}

	return EPHEMERAL_PATTERNS.some((pattern) => pattern.test(text));
}

function classifyMemoryType(text: string): MemoryType {
	if (/\b(prefer|style|convention|policy)\b/i.test(text)) {
		return "preference";
	}
	if (/\b(always|never|must|do not|don't)\b/i.test(text)) {
		return "constraint";
	}
	if (/\b(when|before|after|workflow|process|run )\b/i.test(text)) {
		return "workflow";
	}
	return "fact";
}

function scoreCandidate(text: string, sourceRole: "user" | "assistant"): number {
	let score = sourceRole === "user" ? 0.55 : 0.4;

	if (/\bremember\b/i.test(text)) score += 0.35;
	if (/\b(always|never|must|do not|don't)\b/i.test(text)) score += 0.2;
	if (/\b(from now on|please use|we use|we do not|we don't)\b/i.test(text)) score += 0.15;

	if (score > 0.99) return 0.99;
	return score;
}

export function normalizeMemoryText(text: string): string {
	const compact = text.replace(/\s+/g, " ").trim();
	if (compact.length === 0) return "";
	if (/[.!?]$/.test(compact)) {
		return compact;
	}
	return `${compact}.`;
}

export function normalizeForDedup(text: string): string {
	return text
		.toLowerCase()
		.replace(/[`*_~]/g, "")
		.replace(/[^a-z0-9\s]/g, " ")
		.replace(/\s+/g, " ")
		.trim();
}

function tokenSet(text: string): Set<string> {
	return new Set(
		normalizeForDedup(text)
			.split(" ")
			.filter((token) => token.length > 1),
	);
}

export function similarityScore(a: string, b: string): number {
	const aTokens = tokenSet(a);
	const bTokens = tokenSet(b);

	if (aTokens.size === 0 || bTokens.size === 0) {
		return 0;
	}

	let overlap = 0;
	for (const token of aTokens) {
		if (bTokens.has(token)) overlap += 1;
	}

	const union = aTokens.size + bTokens.size - overlap;
	return union === 0 ? 0 : overlap / union;
}

export function extractTextFromAgentMessage(message: AgentMessage): string {
	switch (message.role) {
		case "user":
		case "assistant":
		case "custom":
			return extractTextFromContent(message.content);
		case "toolResult":
			return "";
		case "bashExecution":
			return [`$ ${message.command}`, message.output].filter(Boolean).join("\n").trim();
		case "branchSummary":
		case "compactionSummary":
			return message.summary.trim();
	}
}

export function extractAutoMemoryCandidates(
	messages: AgentMessage[],
	options: ExtractMemoryOptions,
): MemoryCandidate[] {
	const userMessage = [...messages].reverse().find((message) => message.role === "user");
	const assistantMessage = [...messages].reverse().find((message) => message.role === "assistant");

	const sources: Array<{ role: "user" | "assistant"; text: string }> = [];
	if (userMessage) {
		sources.push({ role: "user", text: extractTextFromAgentMessage(userMessage) });
	}
	if (assistantMessage) {
		sources.push({ role: "assistant", text: extractTextFromAgentMessage(assistantMessage) });
	}

	const candidates = new Map<string, MemoryCandidate>();

	for (const source of sources) {
		for (const sentence of splitIntoSentences(source.text)) {
			if (!MEMORY_PATTERNS.some((pattern) => pattern.test(sentence))) {
				continue;
			}

			if (isLikelyEphemeral(sentence)) {
				continue;
			}

			const normalizedText = normalizeMemoryText(sentence);
			if (!normalizedText) {
				continue;
			}

			const confidence = scoreCandidate(sentence, source.role);
			const key = normalizeForDedup(normalizedText);
			const existing = candidates.get(key);

			const candidate: MemoryCandidate = {
				text: normalizedText,
				normalizedText: key,
				scope: options.scope,
				type: classifyMemoryType(sentence),
				confidence,
			};

			if (!existing || existing.confidence < candidate.confidence) {
				candidates.set(key, candidate);
			}
		}
	}

	return Array.from(candidates.values());
}

export function dedupeMemoryCandidates(
	candidates: MemoryCandidate[],
	existingRecords: MemoryRecord[],
	similarityThreshold: number,
): MemoryCandidate[] {
	const accepted: MemoryCandidate[] = [];
	const knownNormalized = new Set(existingRecords.map((record) => record.normalizedText));

	for (const candidate of candidates) {
		if (knownNormalized.has(candidate.normalizedText)) {
			continue;
		}

		const nearDuplicate = existingRecords.some((record) => {
			if (record.scope !== candidate.scope) return false;
			return similarityScore(record.text, candidate.text) >= similarityThreshold;
		});

		if (nearDuplicate) {
			continue;
		}

		const duplicateInBatch = accepted.some((existing) => {
			if (existing.scope !== candidate.scope) return false;
			return similarityScore(existing.text, candidate.text) >= similarityThreshold;
		});

		if (duplicateInBatch) {
			continue;
		}

		accepted.push(candidate);
		knownNormalized.add(candidate.normalizedText);
	}

	return accepted;
}

export function deriveProjectKey(cwd: string): string {
	const normalized = cwd.replace(/\\/g, "/").replace(/\/+$/g, "");
	const parts = normalized.split("/").filter(Boolean);
	const tail = parts[parts.length - 1] || "project";
	return tail.replace(/[^a-zA-Z0-9._-]/g, "-").toLowerCase();
}

const MEMORY_ID_NAMESPACE = "f3db30bf-4ca3-4e8a-9be9-98a335fdf2e8";

export function makeMemoryId(input: { scope: MemoryScope; projectKey: string; normalizedText: string }): string {
	const base = [input.scope, input.projectKey, input.normalizedText].join("|");
	return uuidv5(base, MEMORY_ID_NAMESPACE);
}

export function rankMemoryRecords(records: MemoryRecord[]): MemoryRecord[] {
	return [...records].sort((a, b) => {
		if (a.updatedAt !== b.updatedAt) {
			return b.updatedAt - a.updatedAt;
		}
		if (a.confidence !== b.confidence) {
			return b.confidence - a.confidence;
		}
		return a.text.localeCompare(b.text);
	});
}

export function buildMemoryContextBlock(records: MemoryRecord[], maxItems: number, maxChars: number): string {
	const ranked = rankMemoryRecords(records);
	const lines: string[] = [];
	let totalChars = 0;

	for (const record of ranked) {
		if (lines.length >= maxItems) break;
		const line = `- [${record.scope}] ${record.text}`;
		const nextLen = totalChars + line.length + 1;
		if (nextLen > maxChars) break;
		lines.push(line);
		totalChars = nextLen;
	}

	if (lines.length === 0) return "";
	return `## Long-term Memory\n${lines.join("\n")}`;
}

export function parseScope(value: string | undefined): MemoryScope {
	if (value === "global" || value === "project") {
		return value;
	}
	return "project";
}

export function toMemoryRecord(input: {
	candidate: MemoryCandidate;
	projectKey: string;
	sourceSessionId: string;
	sourceTurnId?: string;
	timestamp: number;
}): MemoryRecord {
	return {
		id: makeMemoryId({
			scope: input.candidate.scope,
			projectKey: input.projectKey,
			normalizedText: input.candidate.normalizedText,
		}),
		text: input.candidate.text,
		normalizedText: input.candidate.normalizedText,
		scope: input.candidate.scope,
		type: input.candidate.type,
		confidence: input.candidate.confidence,
		projectKey: input.projectKey,
		sourceSessionId: input.sourceSessionId,
		sourceTurnId: input.sourceTurnId,
		createdAt: input.timestamp,
		updatedAt: input.timestamp,
		deleted: false,
	};
}

export function hasStrongMemorySignal(text: string): boolean {
	return STRONG_MEMORY_PATTERNS.some((pattern) => pattern.test(text));
}

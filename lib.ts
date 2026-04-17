import type { SessionEntry } from "../../../src/core/session-manager.js";

export interface LogseqSyncRecord {
	id: string;
	role: string;
	text: string;
	timestamp: number;
}

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

export function buildConversationPageTitle(sessionId: string, sessionTimestamp: string | undefined): string {
	const parsedDate = isValidDate(sessionTimestamp) && sessionTimestamp ? new Date(sessionTimestamp) : new Date();
	const date = formatIsoDate(parsedDate);
	return `Pi Conversation ${date} ${sessionId}`;
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
		} else if (candidate.type === "toolCall" && typeof candidate.name === "string") {
			parts.push(`[toolCall] ${candidate.name} ${JSON.stringify(candidate.arguments ?? {})}`);
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
	let text = "";

	switch (message.role) {
		case "user":
		case "assistant":
		case "toolResult":
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

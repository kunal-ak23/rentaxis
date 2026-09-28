/**
 * Ticket attachment checks shared by the Create Ticket modal and the ticket
 * detail page (break round 2, portal2 F1).
 *
 * The backend caps a multipart file at 10 MB (spring.servlet.multipart
 * max-file-size) and accepts anything else, including an empty file, so the
 * browser refuses those up front with a reason the user can read instead of
 * silently losing them.
 */

export const TICKET_ATTACHMENT_MAX_MB = 10;
export const TICKET_ATTACHMENT_MAX_BYTES = TICKET_ATTACHMENT_MAX_MB * 1024 * 1024;

/** What the Create Ticket modal accepts (the input's `accept` attribute). */
export const TICKET_CREATE_ACCEPT = "image/*,video/*,.pdf";
/** What the ticket detail page accepts. */
export const TICKET_DETAIL_ACCEPT = "image/*,video/*,.pdf,.doc,.docx";

/** Extensions standing in for a wildcard MIME family when the browser gives no usable type. */
const WILDCARD_EXTENSIONS: Record<string, string[]> = {
    "image/*": [".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp", ".tif", ".tiff", ".avif"],
    "video/*": [".mp4", ".mov", ".m4v", ".3gp", ".3g2", ".webm", ".mkv", ".avi"],
};

export type AttachmentRejection = "empty" | "tooLarge" | "wrongType";

/**
 * Whether `file` matches an `accept` list ("image/*", "application/pdf",
 * ".pdf"). A browser's picker only *suggests* the accept list — "All files"
 * and drag-and-drop get past it — so it is checked again here. A file with no
 * MIME type still matches by extension.
 */
export function matchesAccept(file: File, accept: string): boolean {
    const name = file.name.toLowerCase();
    const type = (file.type || "").toLowerCase();
    // A browser that doesn't know the format (HEIC on many Android/desktop
    // browsers, some phone videos) reports "" or a generic binary type; the
    // extension decides then, so a real phone photo is never refused.
    const typeUnknown = type === "" || type === "application/octet-stream";
    const ext = name.includes(".") ? name.slice(name.lastIndexOf(".")) : "";
    return accept
        .split(",")
        .map(s => s.trim().toLowerCase())
        .filter(Boolean)
        .some(token => {
            if (token.startsWith(".")) return name.endsWith(token);
            if (token.endsWith("/*")) {
                if (type.startsWith(token.slice(0, -1))) return true;
                return typeUnknown && (WILDCARD_EXTENSIONS[token] ?? []).includes(ext);
            }
            return type === token;
        });
}

export function rejectAttachment(file: File, accept: string): AttachmentRejection | null {
    if (file.size === 0) return "empty";
    if (file.size > TICKET_ATTACHMENT_MAX_BYTES) return "tooLarge";
    if (!matchesAccept(file, accept)) return "wrongType";
    return null;
}

/** Splits a selection into the files to keep and the refused ones with their reason. */
export function partitionAttachments(files: File[], accept: string) {
    const accepted: File[] = [];
    const rejected: { name: string; reason: AttachmentRejection }[] = [];
    for (const file of files) {
        const reason = rejectAttachment(file, accept);
        if (reason) rejected.push({ name: file.name, reason });
        else accepted.push(file);
    }
    return { accepted, rejected };
}

/** POSTs one file to a ticket's attachments through /api/upload; true only on a 2xx. */
export async function uploadTicketAttachment(ticketId: string, file: File): Promise<boolean> {
    const formData = new FormData();
    formData.append("file", file);
    formData.append("name", file.name);
    try {
        const res = await fetch(`/api/upload?path=/api/v1/tickets/${ticketId}/attachments`, {
            method: "POST",
            body: formData,
        });
        return res.ok;
    } catch {
        return false;
    }
}

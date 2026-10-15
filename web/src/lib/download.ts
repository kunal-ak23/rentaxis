/**
 * Saving a downloaded file under the right name.
 *
 * The backend names an attachment download in its Content-Disposition: the display
 * name the user typed plus the original file's extension ("aadhar" → "aadhar.pdf"),
 * with an RFC 5987 `filename*` for Arabic names. The client used to save the blob
 * under the display name alone, so the file came back with no extension and the OS
 * could not open it.
 */

/** The file name a Content-Disposition header gives, preferring the UTF-8 `filename*`. */
export function filenameFromDisposition(header: string | null | undefined): string | null {
    if (!header) return null;
    const star = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header);
    if (star) {
        try {
            const decoded = decodeURIComponent(star[2].trim().replace(/^"|"$/g, ""));
            if (decoded) return decoded;
        } catch {
            // A malformed escape: fall through to the plain filename.
        }
    }
    const plain = /filename\s*=\s*("((?:[^"\\]|\\.)*)"|[^;]+)/i.exec(header);
    if (plain) {
        const value = (plain[2] ?? plain[1]).trim();
        if (value) return value;
    }
    return null;
}

/** Common extensions by MIME type, for a response without a Content-Disposition name. */
const EXT_BY_TYPE: Record<string, string> = {
    "application/pdf": ".pdf",
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/gif": ".gif",
    "image/webp": ".webp",
    "image/heic": ".heic",
    "video/mp4": ".mp4",
    "video/quicktime": ".mov",
    "text/csv": ".csv",
    "text/plain": ".txt",
    "application/msword": ".doc",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
    "application/vnd.ms-excel": ".xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx",
};

/**
 * The name to save a downloaded file as: the server's Content-Disposition name, else
 * `fallback` with the extension its Content-Type names when it has none.
 */
export function downloadFilename(headers: Headers, fallback: string): string {
    const named = filenameFromDisposition(headers.get("content-disposition"));
    if (named) return named;
    const type = (headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();
    const ext = EXT_BY_TYPE[type];
    if (!ext || fallback.toLowerCase().endsWith(ext)) return fallback;
    return fallback + ext;
}

/** Saves a fetched file under {@link downloadFilename}. */
export async function saveResponse(res: Response, fallback: string): Promise<void> {
    const blob = await res.blob();
    const href = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = href;
    a.download = downloadFilename(res.headers, fallback);
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(href);
}

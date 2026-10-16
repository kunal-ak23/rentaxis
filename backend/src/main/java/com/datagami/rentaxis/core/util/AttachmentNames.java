package com.datagami.rentaxis.core.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * The file name an attachment downloads as. A clerk names a document ("aadhar") and
 * uploads a PDF; the display name is kept as typed, and the download carries the
 * original file's extension ("aadhar.pdf") so the OS can open it.
 *
 * <p>The extension is read from what was stored, so rows uploaded before this needed
 * no migration: the stored blob name keeps the uploaded file's extension, the stored
 * content type names the format, and, when both are missing, the leading bytes do.</p>
 */
public final class AttachmentNames {

    private AttachmentNames() {
    }

    public static final String OCTET_STREAM = "application/octet-stream";

    private static final Map<String, String> EXT_BY_TYPE = Map.ofEntries(
            Map.entry("application/pdf", ".pdf"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/jpg", ".jpg"),
            Map.entry("image/pjpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/heic", ".heic"),
            Map.entry("image/heif", ".heif"),
            Map.entry("image/bmp", ".bmp"),
            Map.entry("image/tiff", ".tiff"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("video/quicktime", ".mov"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/csv", ".csv"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.ms-powerpoint", ".ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
            Map.entry("application/zip", ".zip"));

    private static final Map<String, String> TYPE_BY_EXT = Map.ofEntries(
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".png", "image/png"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".heic", "image/heic"),
            Map.entry(".heif", "image/heif"),
            Map.entry(".bmp", "image/bmp"),
            Map.entry(".tif", "image/tiff"),
            Map.entry(".tiff", "image/tiff"),
            Map.entry(".mp4", "video/mp4"),
            Map.entry(".mov", "video/quicktime"),
            Map.entry(".mp3", "audio/mpeg"),
            Map.entry(".txt", "text/plain"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".ppt", "application/vnd.ms-powerpoint"),
            Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry(".zip", "application/zip"));

    /**
     * The extension ({@code ".pdf"}, lower case) of a file name or stored path, when it
     * has a short alphanumeric one; otherwise {@code ""}. Query strings and fragments
     * (a SAS URL) are ignored.
     */
    public static String extensionOf(String nameOrPath) {
        if (nameOrPath == null) return "";
        String s = nameOrPath;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return lastExtension(s);
    }

    /**
     * The extension of a typed display name. Unlike {@link #extensionOf} it does NOT cut at
     * {@code ?} or {@code #}: those are legal in a file name ({@code quote*2026?.pdf}) and
     * only mean "query / fragment" in a stored URL (S18-03).
     */
    public static String displayExtensionOf(String displayName) {
        return displayName == null ? "" : lastExtension(displayName);
    }

    private static String lastExtension(String s) {
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String base = s.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot <= 0) return "";
        String ext = base.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : "";
    }

    /** The extension for a MIME type ({@code "image/jpeg"} → {@code ".jpg"}), or {@code ""}. */
    public static String extensionForType(String contentType) {
        String t = normalisedType(contentType);
        return t == null ? "" : EXT_BY_TYPE.getOrDefault(t, "");
    }

    /** The MIME type of an extension, or {@code null}. */
    public static String typeForExtension(String ext) {
        return ext == null ? null : TYPE_BY_EXT.get(ext.toLowerCase(Locale.ROOT));
    }

    /** The format the leading bytes announce (PDF, PNG, JPEG, GIF, WebP, ZIP-based Office), or {@code null}. */
    public static String sniffType(byte[] b) {
        if (b == null) return null;
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        String image = ImageTypes.sniff(b).orElse(null);
        if (image != null) return image;
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) return "application/zip";
        return null;
    }

    /**
     * The extension to keep for an upload: the original file's own, else the one its
     * content type names. Stored on the blob name so a download can always recover it.
     */
    public static String uploadExtension(String originalFilename, String contentType) {
        String ext = extensionOf(originalFilename);
        return ext.isEmpty() ? extensionForType(contentType) : ext;
    }

    /**
     * The content type to store for an upload: the declared one, unless the browser
     * sent none or a generic one, then the one the original file's extension names.
     */
    public static String uploadContentType(String originalFilename, String declaredType) {
        String t = normalisedType(declaredType);
        if (t != null && !OCTET_STREAM.equals(t)) return declaredType;
        String byExt = typeForExtension(extensionOf(originalFilename));
        return byExt != null ? byExt : declaredType;
    }

    /**
     * The extension a stored attachment downloads with: from its stored path, else its
     * stored content type, else its leading bytes ({@code head} may be null).
     */
    public static String storedExtension(String storedPath, String storedType, byte[] head) {
        String ext = extensionOf(storedPath);
        if (!ext.isEmpty()) return ext;
        ext = extensionForType(storedType);
        if (!ext.isEmpty()) return ext;
        return extensionForType(sniffType(head));
    }

    /** The Content-Type to serve: the stored one, else what the extension or bytes say, else octet-stream. */
    public static String servedContentType(String storedType, String storedPath, byte[] head) {
        String t = normalisedType(storedType);
        String candidate;
        if (t != null && !OCTET_STREAM.equals(t)) {
            candidate = t;
        } else {
            String byExt = typeForExtension(extensionOf(storedPath));
            candidate = byExt != null ? byExt : sniffType(head);
        }
        // Only formats a browser will not run as a page or script are served as themselves;
        // text/html, SVG, XML, JavaScript and anything unknown or malformed go out as bytes.
        return candidate != null && SAFE_SERVED_TYPES.contains(candidate) ? candidate : OCTET_STREAM;
    }

    /** Content types an attachment download may carry; everything else is served as octet-stream. */
    static final java.util.Set<String> SAFE_SERVED_TYPES = java.util.Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif",
            "video/mp4", "video/quicktime", "audio/mpeg", "application/zip", "text/plain", "text/csv",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    /**
     * The {@link org.springframework.http.MediaType} to serve: a stored value that does not
     * parse (a malformed legacy content type) falls back to octet-stream instead of a 500.
     */
    public static org.springframework.http.MediaType mediaType(String contentType) {
        try {
            return contentType == null || contentType.isBlank()
                    ? org.springframework.http.MediaType.APPLICATION_OCTET_STREAM
                    : org.springframework.http.MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) { // InvalidMediaTypeException, InvalidMimeTypeException
            return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * The file name to download as: the display name as typed, plus the extension when
     * the name does not already end with it ("aadhar" → "aadhar.pdf"; "aadhar.pdf" and
     * "scan.JPEG" for a .jpg stay as they are). A blank name falls back to {@code fallback}.
     */
    public static String downloadName(String displayName, String extension, String fallback) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty()) name = fallback == null || fallback.isBlank() ? "attachment" : fallback;
        if (extension == null || extension.isEmpty()) return name;
        String own = displayExtensionOf(name);
        if (own.equals(extension) || sameFormat(own, extension)) return name;
        return name + extension;
    }

    /** Shorthand: {@link #downloadName} with {@link #storedExtension}. */
    public static String downloadName(String displayName, String storedPath, String storedType, byte[] head, String fallback) {
        return downloadName(displayName, storedExtension(storedPath, storedType, head), fallback);
    }

    /**
     * A safe {@code Content-Disposition} for a free-text file name (RFC 6266 / 5987):
     * {@code filename} is an ASCII fallback with header- and filesystem-special and
     * non-ASCII characters replaced; {@code filename*} is the real name percent-encoded
     * as UTF-8, so an Arabic name round-trips in any current browser.
     */
    public static String contentDisposition(String disposition, String filename) {
        String name = filename == null || filename.isBlank() ? "attachment" : filename;
        String asciiSafe = name.replaceAll("[\"\\r\\n\\\\/:*?<>|;%]", "_").replaceAll("[^\\x20-\\x7E]", "_");
        // URLEncoder leaves "*" bare, which RFC 5987 attr-char does not allow.
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
        return disposition + "; filename=\"" + asciiSafe + "\"; filename*=UTF-8''" + encoded;
    }

    /** A downloaded attachment: its bytes, the name to save it as and the type to serve. */
    public record Download(byte[] content, String filename, String contentType) { }

    /** Builds a {@link Download} for stored bytes, recovering a missing extension from path, type or bytes. */
    public static Download download(byte[] content, String displayName, String storedPath, String storedType, String fallback) {
        return new Download(content,
                downloadName(displayName, storedPath, storedType, head(content), fallback),
                servedContentType(storedType, storedPath, head(content)));
    }

    private static byte[] head(byte[] content) {
        return content == null || content.length <= 16 ? content : java.util.Arrays.copyOf(content, 16);
    }

    public static String attachment(String filename) {
        return contentDisposition("attachment", filename);
    }

    private static boolean sameFormat(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        String ta = TYPE_BY_EXT.get(a);
        return ta != null && ta.equals(TYPE_BY_EXT.get(b));
    }

    private static String normalisedType(String contentType) {
        if (contentType == null) return null;
        String t = contentType.strip().toLowerCase(Locale.ROOT);
        int semi = t.indexOf(';');
        if (semi >= 0) t = t.substring(0, semi).strip();
        return t.isEmpty() ? null : t;
    }
}

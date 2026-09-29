export type ChequeConfidence = "HIGH" | "MEDIUM" | "LOW";

export type ExtractedCheque = {
  chequeNumber: string | null;
  bankName: string | null;
  payerName: string | null;
  /** The name on the "Pay" line; null when unreadable. */
  payeeName?: string | null;
  chequeDate: string | null;
  amount: number | null;
  confidence: ChequeConfidence;
};

export type ChequeImageMeta = {
  url: string;
  blobPath: string;
  uploadedAt: string;
};

/**
 * The organisation's payee check on the read payee (Settings › Organisation);
 * null when the check is off or lists no valid names.
 */
export type PayeeCheck = "MATCH" | "MISMATCH" | "UNREADABLE";

export type ChequeExtractionResponse = {
  image: ChequeImageMeta;
  extracted: ExtractedCheque | null;
  warnings: string[];
  payeeCheck?: PayeeCheck | null;
};

/** Where a cheque sits on its page, normalised 0–1 (top-left corner + size). */
export type ChequeBoundingBox = { x: number; y: number; width: number; height: number };

/** The cheque could not be cut out cleanly; its image is the whole page. */
export const CROP_UNRELIABLE = "crop_unreliable";

/** One cheque found in an upload, with its own server-issued image. */
export type DetectedChequeItem = {
  imageId: string | null;
  image: ChequeImageMeta;
  page: number;
  box: ChequeBoundingBox | null;
  /** Small JPEG data URL of `image`; null when the server could not decode it. */
  thumbnailUrl: string | null;
  extracted: ExtractedCheque | null;
  warnings: string[];
  flags: string[];
  /** The organisation's payee check on this cheque's payee; null when the check is off. */
  payeeCheck?: PayeeCheck | null;
};

/** `POST /cheques/extract-many`: every cheque in one photo or PDF. */
export type ChequeMultiExtractionResponse = {
  original: ChequeImageMeta;
  pages: { page: number; previewUrl: string | null }[];
  items: DetectedChequeItem[];
  warnings: string[];
};

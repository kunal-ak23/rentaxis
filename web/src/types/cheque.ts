export type ChequeConfidence = "HIGH" | "MEDIUM" | "LOW";

export type ExtractedCheque = {
  chequeNumber: string | null;
  bankName: string | null;
  payerName: string | null;
  chequeDate: string | null;
  amount: number | null;
  confidence: ChequeConfidence;
};

export type ChequeImageMeta = {
  url: string;
  blobPath: string;
  uploadedAt: string;
};

export type ChequeExtractionResponse = {
  image: ChequeImageMeta;
  extracted: ExtractedCheque | null;
  warnings: string[];
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
};

/** `POST /cheques/extract-many`: every cheque in one photo or PDF. */
export type ChequeMultiExtractionResponse = {
  original: ChequeImageMeta;
  pages: { page: number; previewUrl: string | null }[];
  items: DetectedChequeItem[];
  warnings: string[];
};

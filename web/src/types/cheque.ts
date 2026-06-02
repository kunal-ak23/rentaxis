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

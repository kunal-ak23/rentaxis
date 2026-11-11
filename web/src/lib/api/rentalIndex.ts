/**
 * DLD rental index ranges on a property (`/api/v1/properties/{id}/rental-index`)
 * and the unit's DEWA premise number. The ranges are typed in from the DLD
 * calculator; nothing here talks to dubailand.gov.ae.
 */
import { ApiError } from "@/lib/api/facilities";

export const UNIT_TYPES = ["STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE", "RETAIL", "OFFICE"] as const;
export type UnitType = (typeof UNIT_TYPES)[number];

/** The DLD calculator, in the operator's language (the Arabic page is a sibling path). */
export function dldRentalIndexUrl(locale: string): string {
  return locale === "ar"
    ? "https://dubailand.gov.ae/ar/eservices/rental-index/"
    : "https://dubailand.gov.ae/en/eservices/rental-index/";
}

export type RentalIndexRange = {
  id: string;
  propertyId: string;
  unitType: UnitType;
  indexYear: number;
  minValue: number;
  maxValue: number;
  sourceNote: string | null;
  enteredBy: string | null;
  enteredAt: string;
};

export type RentalIndexRangeInput = {
  building?: string | null;
  unitType: string;
  indexYear: number;
  minValue: number;
  maxValue: number;
  sourceNote?: string | null;
};

export type RentalIndexImportResult = { saved: number; errors: string[] };

async function parse<T>(res: Response): Promise<T> {
  const text = await res.text().catch(() => "");
  if (!res.ok) {
    let message = `Request failed (status ${res.status})`;
    try {
      const body = JSON.parse(text) as { message?: unknown; errors?: unknown };
      if (typeof body.message === "string") message = body.message;
    } catch { /* not JSON */ }
    throw new ApiError(res.status, message, text);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

const base = (propertyId: string) => `/api/proxy/v1/properties/${encodeURIComponent(propertyId)}/rental-index`;

export async function fetchRentalIndex(propertyId: string): Promise<RentalIndexRange[]> {
  return parse(await fetch(base(propertyId)));
}

export async function upsertRentalIndexRange(propertyId: string, body: RentalIndexRangeInput): Promise<RentalIndexRange> {
  return parse(await fetch(base(propertyId), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }));
}

/**
 * All or nothing: a 400 carries `{saved: 0, errors: ["Row N: …"]}` and is returned
 * (not thrown) so the tab can list the rows; any other failure throws.
 */
export async function importRentalIndex(propertyId: string, rows: RentalIndexRangeInput[]): Promise<RentalIndexImportResult> {
  const res = await fetch(`${base(propertyId)}/import`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(rows),
  });
  if (res.status === 400) {
    const text = await res.text().catch(() => "");
    try {
      const body = JSON.parse(text) as Partial<RentalIndexImportResult> & { message?: string };
      if (Array.isArray(body.errors)) return { saved: body.saved ?? 0, errors: body.errors.map(String) };
      throw new ApiError(400, body.message ?? "The import failed.", text);
    } catch (e) {
      if (e instanceof ApiError) throw e;
      throw new ApiError(400, "The import failed.", text);
    }
  }
  return parse(await Promise.resolve(res));
}

export async function deleteRentalIndexRange(propertyId: string, id: string): Promise<void> {
  await parse<void>(await fetch(`${base(propertyId)}/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function setUnitDewaPremiseNumber(unitId: string, dewaPremiseNumber: string | null): Promise<{ id: string; dewaPremiseNumber: string | null }> {
  return parse(await fetch(`/api/proxy/v1/units/${encodeURIComponent(unitId)}/dewa-premise-number`, {
    method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ dewaPremiseNumber }),
  }));
}

/** Nine digits, or blank (which clears the number). */
export function dewaPremiseNumberValid(value: string): boolean {
  const s = value.trim();
  return s === "" || /^\d{9}$/.test(s);
}

/** One parsed CSV row, or why it could not be parsed (the row number counts the header as 1). */
export type ParsedCsvRow = { row: number; input: RentalIndexRangeInput | null; error: string | null };

/** Split one CSV line on commas, honouring double quotes ("" inside quotes is a quote). */
function splitCsvLine(line: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"') {
        if (line[i + 1] === '"') { cur += '"'; i++; } else quoted = false;
      } else cur += c;
    } else if (c === '"') quoted = true;
    else if (c === ",") { out.push(cur); cur = ""; }
    else cur += c;
  }
  out.push(cur);
  return out.map(s => s.trim());
}

/** "83,736" and "AED 83736" both read as 83736; anything else is NaN. */
function amount(raw: string): number {
  const s = raw.replace(/aed/i, "").replace(/[,\s]/g, "");
  return s === "" ? NaN : Number(s);
}

/**
 * Parse a "building, unit type, year, min, max[, note]" sheet. The first line is
 * taken as a header when it is not numeric in the year column, and the server
 * re-validates every row (unit-type spellings, the building name, duplicates).
 */
export function parseRentalIndexCsv(text: string): ParsedCsvRow[] {
  const lines = text.split(/\r?\n/);
  const rows: ParsedCsvRow[] = [];
  lines.forEach((line, idx) => {
    if (line.trim() === "") return;
    const cells = splitCsvLine(line);
    const rowNo = idx + 1;
    if (idx === 0 && !/^\d{4}$/.test(cells[2] ?? "")) return; // header
    if (cells.length < 5) {
      rows.push({ row: rowNo, input: null, error: "expected 5 columns: building, unit type, year, min, max" });
      return;
    }
    const [building, unitType, yearRaw, minRaw, maxRaw, note] = cells;
    const year = Number(yearRaw);
    const min = amount(minRaw);
    const max = amount(maxRaw);
    if (!unitType) { rows.push({ row: rowNo, input: null, error: "unit type is blank" }); return; }
    if (!/^\d{4}$/.test(yearRaw)) { rows.push({ row: rowNo, input: null, error: `year "${yearRaw}" is not a four-digit year` }); return; }
    if (!Number.isFinite(min) || !Number.isFinite(max)) { rows.push({ row: rowNo, input: null, error: "min and max must be amounts" }); return; }
    rows.push({ row: rowNo, error: null, input: { building: building || null, unitType, indexYear: year, minValue: min, maxValue: max, sourceNote: note || null } });
  });
  return rows;
}

import { describe, expect, it } from "vitest";
import { autoMapChequesToRows } from "../autoMapChequesToRows";

const rows = [
  { id: "s1", dueDate: "2026-06-05" },
  { id: "s2", dueDate: "2026-07-05" },
  { id: "s3", dueDate: "2026-08-05" },
];

describe("autoMapChequesToRows", () => {
  it("maps each cheque to its closest cheque row greedily", () => {
    const items = [
      { id: "i1", chequeDate: "2026-08-04", pinned: false, assignedRowId: null },
      { id: "i2", chequeDate: "2026-06-06", pinned: false, assignedRowId: null },
      { id: "i3", chequeDate: "2026-07-08", pinned: false, assignedRowId: null },
    ];
    const result = autoMapChequesToRows(items, rows);
    expect(result.get("i1")).toBe("s3");
    expect(result.get("i2")).toBe("s1");
    expect(result.get("i3")).toBe("s2");
  });

  it("skips items with no chequeDate", () => {
    const items = [
      { id: "i1", chequeDate: null, pinned: false, assignedRowId: null },
      { id: "i2", chequeDate: "2026-06-04", pinned: false, assignedRowId: null },
    ];
    const result = autoMapChequesToRows(items, rows);
    expect(result.has("i1")).toBe(false);
    expect(result.get("i2")).toBe("s1");
  });

  it("reserves rows locked by pinned items", () => {
    const items = [
      { id: "i1", chequeDate: "2026-06-04", pinned: true, assignedRowId: "s1" },
      { id: "i2", chequeDate: "2026-06-06", pinned: false, assignedRowId: null },
    ];
    const result = autoMapChequesToRows(items, rows);
    // i1 is pinned, so it isn't in the result map. i2 should NOT take s1.
    expect(result.has("i1")).toBe(false);
    expect(result.get("i2")).toBe("s2");
  });

  it("returns no mapping for cheques when all rows are taken", () => {
    const items = [
      { id: "i1", chequeDate: "2026-06-04", pinned: false, assignedRowId: null },
      { id: "i2", chequeDate: "2026-06-05", pinned: false, assignedRowId: null },
      { id: "i3", chequeDate: "2026-06-06", pinned: false, assignedRowId: null },
      { id: "i4", chequeDate: "2026-06-07", pinned: false, assignedRowId: null },
    ];
    const result = autoMapChequesToRows(items, rows);
    expect(result.size).toBe(3); // 4th cheque is unmapped
  });

  it("breaks a date tie by the closer amount", () => {
    // Both rows sit exactly 15 days from the cheque date and within the amount
    // tolerance of the scan; the exact amount wins the tie.
    const tiedRows = [
      { id: "s1", dueDate: "2026-06-20", amount: 5001 },
      { id: "s2", dueDate: "2026-07-20", amount: 5000 },
    ];
    const items = [{ id: "i1", chequeDate: "2026-07-05", amount: 5000, pinned: false, assignedRowId: null }];
    const result = autoMapChequesToRows(items, tiedRows);
    expect(result.get("i1")).toBe("s2");
  });

  it("never lets amount override a closer date", () => {
    // Both within the tolerance: s1 is 1 day away, s2 is 30 days away with the exact amount.
    const rowsByAmount = [
      { id: "s1", dueDate: "2026-06-06", amount: 4999.5 },
      { id: "s2", dueDate: "2026-07-05", amount: 5000 },
    ];
    const items = [{ id: "i1", chequeDate: "2026-06-05", amount: 5000, pinned: false, assignedRowId: null }];
    const result = autoMapChequesToRows(items, rowsByAmount);
    expect(result.get("i1")).toBe("s1");
  });

  // PR #396 review P3-4: a scan of a different amount is not mapped by default.
  it("never auto-maps onto a row whose amount differs by more than the tolerance", () => {
    const rows = [
      { id: "s1", dueDate: "2026-06-05", amount: 4000 },
      { id: "s2", dueDate: "2026-09-05", amount: 5000 },
    ];
    expect(autoMapChequesToRows([{ id: "i1", chequeDate: "2026-06-05", amount: 5000, pinned: false, assignedRowId: null }], rows).get("i1")).toBe("s2");
    expect(autoMapChequesToRows([{ id: "i1", chequeDate: "2026-06-05", amount: 7000, pinned: false, assignedRowId: null }], rows).has("i1")).toBe(false);
    // No amount read: date alone, as before.
    expect(autoMapChequesToRows([{ id: "i1", chequeDate: "2026-06-05", amount: null, pinned: false, assignedRowId: null }], rows).get("i1")).toBe("s1");
  });
});

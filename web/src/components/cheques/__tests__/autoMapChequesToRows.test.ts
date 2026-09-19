import { describe, expect, it } from "vitest";
import { autoMapChequesToSchedules } from "../autoMapChequesToSchedules";

const schedules = [
  { id: "s1", dueDate: "2026-06-05" },
  { id: "s2", dueDate: "2026-07-05" },
  { id: "s3", dueDate: "2026-08-05" },
];

describe("autoMapChequesToSchedules", () => {
  it("maps each cheque to its closest due date greedily", () => {
    const items = [
      { id: "i1", chequeDate: "2026-08-04", pinned: false, assignedScheduleId: null },
      { id: "i2", chequeDate: "2026-06-06", pinned: false, assignedScheduleId: null },
      { id: "i3", chequeDate: "2026-07-08", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    expect(result.get("i1")).toBe("s3");
    expect(result.get("i2")).toBe("s1");
    expect(result.get("i3")).toBe("s2");
  });

  it("skips items with no chequeDate", () => {
    const items = [
      { id: "i1", chequeDate: null, pinned: false, assignedScheduleId: null },
      { id: "i2", chequeDate: "2026-06-04", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    expect(result.has("i1")).toBe(false);
    expect(result.get("i2")).toBe("s1");
  });

  it("reserves schedules locked by pinned rows", () => {
    const items = [
      { id: "i1", chequeDate: "2026-06-04", pinned: true, assignedScheduleId: "s1" },
      { id: "i2", chequeDate: "2026-06-06", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    // i1 is pinned, so it isn't in the result map. i2 should NOT take s1.
    expect(result.has("i1")).toBe(false);
    expect(result.get("i2")).toBe("s2");
  });

  it("returns no mapping for cheques when all schedules are taken", () => {
    const items = [
      { id: "i1", chequeDate: "2026-06-04", pinned: false, assignedScheduleId: null },
      { id: "i2", chequeDate: "2026-06-05", pinned: false, assignedScheduleId: null },
      { id: "i3", chequeDate: "2026-06-06", pinned: false, assignedScheduleId: null },
      { id: "i4", chequeDate: "2026-06-07", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    expect(result.size).toBe(3); // 4th cheque is unmapped
  });
});

export type AutoMapItem = {
  id: string;
  chequeDate: string | null;
  pinned: boolean;
  assignedScheduleId: string | null;
};

export type AutoMapSchedule = {
  id: string;
  dueDate: string;
};

function dayDistance(a: string, b: string): number {
  const ay = Number(a.slice(0, 4));
  const am = Number(a.slice(5, 7)) - 1;
  const ad = Number(a.slice(8, 10));
  const by = Number(b.slice(0, 4));
  const bm = Number(b.slice(5, 7)) - 1;
  const bd = Number(b.slice(8, 10));
  return Math.abs(Math.round((Date.UTC(ay, am, ad) - Date.UTC(by, bm, bd)) / 86_400_000));
}

export function autoMapChequesToSchedules(
  items: AutoMapItem[],
  schedules: AutoMapSchedule[]
): Map<string, string> {
  // Reserve schedules locked by pinned rows.
  const remaining = new Set(schedules.map(s => s.id));
  for (const item of items) {
    if (item.pinned && item.assignedScheduleId) {
      remaining.delete(item.assignedScheduleId);
    }
  }

  // Build all (item, schedule) pairs for non-pinned, dated items.
  const pairs: { itemId: string; scheduleId: string; dist: number }[] = [];
  for (const item of items) {
    if (item.pinned || !item.chequeDate) continue;
    for (const s of schedules) {
      if (!remaining.has(s.id)) continue;
      pairs.push({ itemId: item.id, scheduleId: s.id, dist: dayDistance(item.chequeDate, s.dueDate) });
    }
  }
  pairs.sort((a, b) => a.dist - b.dist);

  const assigned = new Set<string>();
  const result = new Map<string, string>();
  for (const p of pairs) {
    if (assigned.has(p.itemId) || !remaining.has(p.scheduleId)) continue;
    result.set(p.itemId, p.scheduleId);
    assigned.add(p.itemId);
    remaining.delete(p.scheduleId);
  }
  return result;
}

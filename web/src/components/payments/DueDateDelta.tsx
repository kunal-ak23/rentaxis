type Props = {
  dueDate: string | null | undefined;
  chequeDate: string | null | undefined;
  className?: string;
};

function daysBetween(a: string, b: string): number {
  const dayA = Date.UTC(
    Number(a.slice(0, 4)),
    Number(a.slice(5, 7)) - 1,
    Number(a.slice(8, 10))
  );
  const dayB = Date.UTC(
    Number(b.slice(0, 4)),
    Number(b.slice(5, 7)) - 1,
    Number(b.slice(8, 10))
  );
  return Math.round((dayB - dayA) / 86_400_000);
}

export default function DueDateDelta({ dueDate, chequeDate, className }: Props) {
  if (!dueDate || !chequeDate) return null;
  const delta = daysBetween(dueDate, chequeDate); // chequeDate − dueDate
  const isAfter = delta > 0;
  const tone = isAfter
    ? "border-red-300 bg-red-50 text-red-700"
    : "border-emerald-300 bg-emerald-50 text-emerald-700";
  const sign = delta === 0 ? "0d" : delta > 0 ? `+${delta}d` : `−${Math.abs(delta)}d`;
  return (
    <span
      className={
        "inline-flex items-center rounded-full border px-1.5 py-0.5 text-[11px] font-medium " +
        tone +
        (className ? " " + className : "")
      }
      title={`Cheque date is ${delta === 0 ? "exactly" : `${Math.abs(delta)} day${Math.abs(delta) === 1 ? "" : "s"} ${isAfter ? "after" : "before"}`} the due date`}
    >
      {sign}
    </span>
  );
}

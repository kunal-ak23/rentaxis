/** Whole AED with thousands separators, for caps ("126,000"). */
export const wholeAed = (n: number) => n.toLocaleString("en-US", { maximumFractionDigits: 0 });

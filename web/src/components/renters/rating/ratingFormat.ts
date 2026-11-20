import type { RatingGrade } from "@/lib/api/renterRatings";

export const GRADE_CLASS: Record<RatingGrade, string> = {
    A: "bg-green-100 text-green-800 border-green-300",
    B: "bg-teal-100 text-teal-800 border-teal-300",
    C: "bg-amber-100 text-amber-800 border-amber-300",
    D: "bg-red-100 text-red-800 border-red-300",
    NEW: "bg-gray-100 text-gray-700 border-gray-300",
};

/**
 * The grade as shown on chips and badges: the letter for A-D, which reads the same in every locale,
 * and the translated word for NEW (pass t("grade.NEW")), never the raw enum name.
 */
export function gradeText(grade: RatingGrade, newLabel: string): string {
    return grade === "NEW" ? newLabel : grade;
}

/** Signed to one decimal with a real minus sign (U+2212): "−7.5", "+1.0". */
export function signedPoints(n: number): string {
    const abs = Math.abs(n).toFixed(1);
    if (n < 0 && Number(abs) !== 0) return `−${abs}`;
    return n > 0 ? `+${abs}` : abs;
}

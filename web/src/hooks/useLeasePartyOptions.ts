import { useMemo } from "react";
import type { SearchableSelectOption } from "@/components/ui/SearchableSelect";

type PartyUnit = {
    id: string;
    unitNumber: string;
    status: string;
    property?: { nameEn?: string; type?: string };
};

type PartyRenter = {
    id: string;
    nameEn: string;
    nameAr?: string;
    email?: string;
};

/**
 * Builds SearchableSelect option lists for the Unit/Renter pickers shared by
 * LeaseWizard.tsx (new lease) and LeaseMetadataEditor.tsx (edit draft lease)
 * — same fields, same search behavior, only the unit label format differs
 * between the two call sites.
 */
export function useLeasePartyOptions(
    units: PartyUnit[],
    renters: PartyRenter[],
    selectedUnitId: string,
    unitLabel: (unit: PartyUnit) => string = (u) => u.unitNumber,
) {
    const unitOptions = useMemo<SearchableSelectOption[]>(
        () =>
            units
                .filter((u) => u.status === "VACANT" || u.id === selectedUnitId)
                .map((u) => ({
                    value: u.id,
                    label: unitLabel(u),
                    sublabel: `${u.property?.nameEn || "—"}${u.property?.type === "COMMERCIAL" ? " [Commercial]" : ""}`,
                    searchText: `${u.unitNumber} ${u.property?.nameEn || ""}`.toLowerCase(),
                })),
        // eslint-disable-next-line react-hooks/exhaustive-deps -- unitLabel is a stable formatter passed by the caller, not reactive state
        [units, selectedUnitId],
    );

    const renterOptions = useMemo<SearchableSelectOption[]>(
        () =>
            renters.map((r) => ({
                value: r.id,
                label: r.nameAr ? `${r.nameEn} (${r.nameAr})` : r.nameEn,
                sublabel: r.email,
                searchText: `${r.nameEn} ${r.nameAr || ""} ${r.email || ""}`.toLowerCase(),
            })),
        [renters],
    );

    return { unitOptions, renterOptions };
}

/**
 * "Fill from Miftah": a bookmark that runs on DLD's rental index page (not ours) and fills its form from what
 * "Copy for DLD" put on the clipboard. The person still does the reCAPTCHA and Calculate. The alert strings are
 * English on purpose: the script runs on DLD's page, outside Miftah's translations.
 */
export type DldTypeLabel = "Apartment" | "Villa" | "Hotel Apartment";

export const FILL_SOURCE = `(async () => {
  const $ = window.jQuery;
  if (!location.hostname.endsWith('dubailand.gov.ae') || !$ || !document.getElementById('dewaNumber')) { alert('Open the DLD Rental Index page (Residential) first, then click this again.'); return; }
  let text;
  try { text = await navigator.clipboard.readText(); } catch (e) { alert('Allow clipboard access, then click again.'); return; }
  if (!text.startsWith('MIFTAH-DLD:')) { alert('Copy the details from Miftah first (Copy for DLD).'); return; }
  const d = JSON.parse(text.slice('MIFTAH-DLD:'.length));
  const pause = (ms) => new Promise((r) => setTimeout(r, ms));
  const choose = async (sel, label) => {
    const c = $(sel).next('.chosen-container');
    c.find('a.chosen-single').trigger('mousedown');
    await pause(200);
    c.find('li.active-result').filter((i, e) => e.textContent.trim() === label).first().trigger('mouseover').trigger('mouseup');
    await pause(400);
  };
  if ($('#ContractEndDate').datepicker) $('#ContractEndDate').datepicker('setDate', d.end);
  $('#ContractEndDate').val(d.end).trigger('change');
  await choose('#searchWithDDL', 'DEWA (Premise No.)');
  await choose('#selectPropertyType', d.typeLabel);
  $('#dewaNumber').val(d.number).trigger('input').trigger('change');
  $('#annualRent').val(String(d.rent)).trigger('input').trigger('change');
  $('#dewaNumber')[0].scrollIntoView({ block: 'center' });
})();`;

/** `end` is dd/mm/yyyy, as DLD's date box wants it. */
export type DldFillDetails = { end: string; typeLabel: DldTypeLabel; number: string; rent: number };

export function bookmarkletHref(): string {
    return "javascript:" + encodeURIComponent(FILL_SOURCE);
}

export function clipboardPayload(d: DldFillDetails): string {
    return "MIFTAH-DLD:" + JSON.stringify({ end: d.end, typeLabel: d.typeLabel, number: d.number, rent: d.rent });
}

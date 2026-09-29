# REJECTED 41 take 1 (2026-09-29)

The capture stopped in the last scene: the proof read the VAT return status right after choosing
the July quarter, before the page had re-read it ("expected Not filed, found Filed ... 2026Q2").
No video was made; the raw clips are in tutorials/raw/tutorial-41-Po28Np.
The same take showed posting the contract took ~16 s on the freshly restored backend (2.6 s in
the proof), pushing every later scene 15 s late.

Fixes: wait for the status text instead of reading it once; the runner can anchor continuing
scenes to their planned start (`anchored: true`), so a slow step is caught up by the next scenes.

# REJECTED 39 take 1 (2026-09-29)

Gates passed. Frame review: from cue 11 the pointer sits on "Download cut-over template" for
~20 s and every later scene starts ~19 s late. The scene pointed at the upload control by its
test id, which is a hidden file input: the capture waited the full 30 s timeout for it to become
visible. The proof only checked that the target was attached.

Fix: pointAt now requires a *visible* target in the proof; the scene points at the visible
"Upload cut-over workbook" button.

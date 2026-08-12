# Bundled application fonts

These font files are bundled so the mobile applications never need to fetch
fonts at runtime. The families are distributed by Google Fonts under the SIL
Open Font License 1.1:

- Cinzel
- Inter
- JetBrains Mono
- Josefin Sans
- Noto Naskh Arabic

Added for the Miftah 2026 redesign (`lib/ui/miftah_tokens.dart`):

- Plus Jakarta Sans — the single UI family; weights 400/500/600/700/800.
  Upstream source: https://github.com/tokotype/PlusJakartaSans (OFL 1.1).
  Google Fonts ships this family variable-only, which `google_fonts` cannot
  resolve from assets, so the static instances come from upstream.
- IBM Plex Mono — codes, plate/cheque numbers, reference IDs; weights 400/500.

Note: the apps set `GoogleFonts.config.allowRuntimeFetching = false`, so every
weight referenced by `MiftahType` must exist here as a static
`Family-Variant.ttf` or the app throws at first paint.

Source: https://fonts.google.com/
License: https://openfontlicense.org/open-font-license-official-text/

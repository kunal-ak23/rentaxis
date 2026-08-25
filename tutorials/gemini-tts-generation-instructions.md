# Gemini TTS narration handoff

Use the **Voice Library / text-to-speech** experience in Google AI Studio, not
Gemini Audio Overview. Select `Gemini 3.1 Flash TTS Preview`, single speaker,
and one voice for the entire library. `Sulafat` (warm) is the recommended
starting voice; `Sadaltager` (knowledgeable) is the alternate.

For every file in `tutorials/narration`, submit this direction followed by the
file's complete text:

> Read the script verbatim as a polished, confident property-management
> software educator. Sound warm, knowledgeable, naturally expressive, and
> proficient. Use varied intonation and short pauses between workflow
> sections. Keep the delivery professional and calm, never theatrical or
> salesy. Aim for approximately 125 words per minute. Pronounce RentAxis as
> “Rent Axis” and AED as “UAE dirhams.” Do not summarize, paraphrase, add, or
> omit any words. Begin with: “This tutorial uses an AI-generated voice.” Then
> read the supplied script exactly.

Download the result as WAV when possible. Preserve the narration filename,
changing only the extension. For example:

```text
tutorials/narration/04-provision-organizations-and-manage-feature-access.txt
  -> tutorials/audio-generated/04-provision-organizations-and-manage-feature-access.wav
```

MP3, M4A, AAC, FLAC, and WAV are accepted. Do not place two formats for the
same tutorial in the folder. The renderer normalizes loudness, pads the final
video frame if needed, muxes the generated track into a 1080p H.264/AAC MP4,
and validates the result before replacing the previous tutorial.

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/2.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.1] - 2026-07-04

### Fixed

- Head icons in the HUD and Players screen showed the skin's right arm instead of the face for modern 64x64 skins

## [0.5.0] - 2026-07-04

### Added

- Opus packet loss concealment (PLC) in the reception pipeline: an isolated lost/late packet is now masked with a synthesized 20ms frame (`decode(null)`) instead of stalling playback into an AL underrun hiccup. Concealment only fires for genuine mid-stream loss - the missing slot's playback time has passed, a later frame is already buffered (so a sender pause is never mistaken for loss and no audio is ever hallucinated after speech ends), and that later frame isn't itself already due (after a burst outage playback skips ahead to live audio rather than replaying the gap late and dragging extra latency for the rest of the segment) - capped at 5 consecutive synthesized frames (~100ms). A concealed slot consumes its sequence number, so a packet that arrives after its slot was concealed is discarded instead of being fed to the stateful decoder out of order
- Per-player volume and mute, client-side only: mute drops a speaker's audio at the UDP receive path itself, before a `VoiceSource`/decoder is ever created or fed, so a muted player never appears as speaking; volume is applied as `AL_GAIN` on the speaker's positioned AL source and survives an output-device/HRTF rebuild since it's re-read from the live setting on every packet rather than cached. Both are set from a new "Players" screen (opened from the existing settings GUI), which lists every other player currently in voice with their head icon, a volume slider, and a mute toggle; the who's-talking HUD gains a muted-marker row alongside the existing self-mute indicator. Persisted in the existing client config file, pruned back to defaults automatically

### Changed

- Settings GUI rebuilt on ModularUI2 (GTNH's GUI framework, now a required dependency - present in any GTNH pack): both the main voice settings screen and the per-player volume/mute screen are declarative MUI2 panels instead of hand-rolled `GuiScreen` code, dropping the custom scroll/scissor/scrollbar plumbing in favor of the framework's scrollable lists, live value bindings (control labels now always reflect current state without manual relabeling), and proper slider/toggle widgets. Player names in the Players screen scroll horizontally when too long instead of being "..."-truncated, and Done/Escape from the Players screen now returns to the main settings screen instead of closing to the game (Escape previously skipped the parent screen). Save semantics unchanged: toggles/cycles persist on click, sliders update live in memory and hit disk once on screen close
- Voice playback latency reduced by ~40ms at the start of each speech segment (and ~20ms steady-state): the jitter buffer's base pre-buffer drops from 2 frames to 1 (40ms -> 20ms; safe now that it always priority-orders by sequence number, and the adaptive component still grows it under real network jitter), and the AL source prime threshold drops from 3 buffers to 2 (60ms -> 40ms) since packet-loss concealment now keeps frames flowing through genuine packet gaps instead of relying on queue depth to ride them out. The receive-side decode poller is also event-driven now instead of sleeping a fixed 20ms tick: it blocks on the jitter buffer's own schedule (head frame due, concealable gap slot going overdue, or a new packet arriving) and wakes at the exact moment there's work, cutting up to ~20ms of reaction quantization to OS timer precision - emission pacing is unchanged, still governed by the buffer's 20ms due-time schedule, and an idle source's poller now sleeps indefinitely instead of waking 50 times a second to poll an empty buffer

### Fixed

- Audible crackle during speech playback caused by an AL source restarting 5-10x/sec on single-buffer underruns: `PlaybackThread` now primes each source (3 buffers/60ms) before starting or restarting it, with a 60ms tail-flush so short utterances that never reach the prime threshold still play out in full instead of sitting stuck. Also fixes a stall, surfaced by this change, where a source left in `AL_STOPPED` (e.g. after a speech-segment inactivity reset) would never resume: OpenAL marks a stopped source's entire buffer queue as processed instantly, even buffers that were never played, silently discarding every newly queued frame until the source is explicitly rewound to `AL_INITIAL`

## [0.4.0] - 2026-07-04

### Added

- Microphone self-mute (default keybind: `M`): hard-mutes the local mic via `alcCaptureStop` on the capture device itself, rather than closing/reopening it, resuming with `alcCaptureStart` and a stale-buffer drain on unmute so the first frame after unmuting isn't a stop/start artifact; capture, activation gate, and the local speaking/HUD state all reflect the mute immediately, client-side only
- Head icons (face + hat layer) in the who's-talking HUD, drawn to the left of each speaking player's name: reuses a loaded player's own resolved skin when available, otherwise lazily triggers and caches a one-shot `SkinManager` lookup by UUID for speakers outside render range, falling back to the default Steve head whenever a skin can't (yet) be resolved

## [0.3.2] - 2026-07-04

## Fixed

- Missed a dependency

## [0.3.1] - 2026-07-04

### Fixed

- Native libraries not included in final JAR

## [0.3.0] - 2026-07-04

### Added

- Server-synced voice roster (UUID -> player name) over the reliable control channel, sent as a full snapshot when a player's voice session is established and kept current via add/remove deltas as players join/leave voice; lets the client resolve a speaker's name from its UUID, which MC 1.7.10's tab list cannot provide on its own
- Who's-talking HUD: a compact top-left overlay listing every player currently speaking (by name, with a short-UUID fallback for the brief window before a just-joined speaker's roster entry arrives), including the local player when transmitting; disable via the `voice.hudEnabled` config option
- Live audio device/HRTF control API (`AudioDeviceController`): hot-swap the capture (microphone) device without interrupting the voice session, and hot-swap the output device and/or HRTF mode (AUTO/ON/OFF) via an ordered playback-context rebuild that recreates each speaker's positioned AL source lazily without losing its decoder/jitter state; falls back to the default device (or AUTO HRTF) on failure instead of leaving audio dead, and persists the chosen input/output device and HRTF mode via new `voice.inputDevice`/`voice.outputDevice`/`voice.hrtfMode` config options
- In-game settings GUI (default keybind: `,`), a scrollable screen exposing activation mode (VA/PTT), VA threshold and hangover sliders, HUD and denoise toggles, and input/output device + HRTF mode selectors; every control applies live and persists immediately, reusing `AudioDeviceController` for device/HRTF hotswaps and remaining usable (values still editable and saved) while disconnected from a voice session

## [0.2.0] - 2026-07-04

### Added

- Native Opus encoder/decoder (ported from Plasmo Voice) as an optional acceleration over the pure-Java codec, automatically falling back to it if the native library isn't available on a given platform
- Optional RNNoise noise suppression (ported from Plasmo Voice) applied to captured mic audio before Opus encoding; native-only with no pure-Java fallback, so voice keeps working unfiltered if the native library fails to load, and can be disabled via config or the `gtnhvoice.disableRnnoise` system property

### Changed

- Replaced the placeholder jitter buffer with an adaptive jitter buffer (ported from Plasmo Voice) that adjusts its buffering delay to observed network jitter instead of a fixed depth

## [0.1.0] - 2026-07-03

### Added

- Initial mod architecture
- Voice activation and push-to-talk
- Voice packet routing on the server
- Java-based Opus encoder/decoder
- OpenAL audio playback on the client for received voice packets
- A simple jitter buffer for incoming voice packets

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/2.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `GtnhVoiceApi.proximityRange()`: the server's effective proximity range in blocks, `min(distance, maxDistance)` from its voice config. The built-in local group cuts off at exactly this range and reads it from the same place, so the two can't drift apart. It reads the live config on every call and is safe to call from `IGroup.route()` on the network thread (server API 1.3)
- `RoutingContext.isServed(UUID)`: whether the frame being routed already went to a player, from a higher-priority group or from the current one, so a group can act only on sends that reach someone. `sendTo` still returns nothing; its javadoc now lists the three cases where it drops a send silently, each with the check that predicts it: no live session for the player (not in `getSessionsByPlayerUuid()`), no UDP address yet (`IVoiceSession.hasUdpAddress()`), already served this frame (`isServed`) (group API 1.2)
- `IPlaybackFrame.x()`, `y()` and `z()`: the world position a positional frame plays from, the one its packet carried, and NaN for a flat frame, so a playback stage can fade a frame by its distance from the listener. The position travels with each frame through the jitter buffer, as the group does, and a concealment frame keeps the position of the real frame it continues; no protocol change (client API 2.4)
- `RoutingTestKit`, for addon unit tests of `IGroup.route()` that import nothing outside the api packages: `session(uuid, name)` and `sessionWithoutUdpAddress(uuid, name)` make sessions, `frame(sequence, activationId)` makes an `IAudioFrame`, and `route(speaker, frame, positions, membershipTest, groups...)` routes it through the groups in the server's priority order, on the server's own `RoutingContext`, returning each delivery as plain values: recipient, source state, x, y, z, source id and group. `RoutingContext.builder()` still works as before (group API 1.2)
- Group names in each player's language: `IGroup.getDisplayComponent()` returns an `IChatComponent`, null by default to keep `getDisplayName()`. The server sends it as JSON with the group table and the group update, and each client formats it with its own lang files wherever the group's name shows: the HUD's speaker rows and self row, and `IPlaybackFrame.groupDisplayName()`. A client formats it again after a language change and shows `getDisplayName()` when it has no translation for the component's key; a component that throws or can't be sent is left out with a warning (group API 1.2)

### Changed

- Voice protocol bumped to v7 (group table entries and the group update carry each group's display component): 0.11.0 clients and servers no longer talk to 0.10.x ones - the voice handshake rejects the version mismatch, so update both sides together

## [0.10.1] - 2026-10-11

### Fixed

- JitPack builds work again, broken since 0.9.2: JitPack's image failed to install JDK 25, so `jitpack.yml` now asks for JDK 21, which only launches Gradle 9.3.1; Gradle then runs the build on a JDK 25 daemon it downloads from foojay. That download was broken too - `gradle/gradle-daemon-jvm.properties` required JDK 25 but its URLs fetched JDK 21, which Gradle rejected - and is regenerated, which also fixes building from source on any machine without a JDK 25. 0.9.2 and 0.10.0 stay unbuildable on JitPack; depend on 0.10.1 or later
- The published POM no longer lists `it.unimi.dsi:fastutil-core` as a runtime dependency: in a consumer's dev environment that also has GregTech it hid the full fastutil GregTech needs, and GregTech crashed in preInit with a `NoSuchMethodError` on `MaterialsIDMap.forEach`. fastutil is now compile-time only; at runtime the full fastutil keeps coming from GTNHLib, which ModularUI2 already requires. The mod jar itself is unchanged

## [0.10.0] - 2026-10-11

### Added

- Frame-aware playback filters for client addons: `audio().playbackFrameFilter((frame, pcm) -> ...)` gets an `IPlaybackFrame` per frame (speaker, internal and display name of the group that routed that exact frame, positional flag); the group travels with each frame through the jitter buffer, and concealment frames keep the last real frame's group (client API 2.1)
- Frame-aware playback chains: `when(frame -> ..., branch -> ...)` runs a sub-chain only on matching frames (per-speaker state, reset when re-entered after a skipped frame; a throwing condition skips just its branch), and custom chain stages can override `Stage.beginFrame(IPlaybackFrame)`; capture chains reject both at registration (client API 2.2)
- Addon activations (push-to-talk for items): `addon.activation("radio").setKeyed(true)`, callable from any thread, opens the mic regardless of the activation mode (no VA threshold, no PTT key; hard mute still wins), and `isTransmitting()` drives "on air" UI. Frames sent while keyed carry the activation's id, a name-based UUID of `"<addonName>:<activationName>"` that servers derive with `ActivationIds.of(...)`; all other frames carry the nil `ActivationIds.DEFAULT`; with several activations keyed, the most recently keyed wins. Keyed state lasts one voice session: when the session ends (disconnect, or a new handshake replacing it) every activation is unkeyed, and a new session never transmits under an activation until the addon keys it again - re-asserting the key every tick, the documented pattern, carries on unaffected, while an addon that forgets to unkey leaves the mic closed on the next connection instead of opening it. Server groups read the id as `IAudioFrame.getActivationId()`, a client claim to treat as intent, never as authorization. The id rides the existing `PlayerAudioPacket` field, so the wire format and protocol version are unchanged (client API 2.3, server API 1.1)
- Server session lifecycle events: `GtnhVoiceApi.addSessionListener(...)` reports `sessionStarted(session)` once the voice handshake is accepted (before the first UDP datagram; `hasUdpAddress()` says when audio can reach the player) and `sessionEnded(session, reason)` with `LOGOUT`, `TIMED_OUT`, `REPLACED` or `SERVER_STOPPED`. Events arrive on the server thread through the tick queue, in per-player order: memberships are wiped before a logout or timeout's `sessionEnded`, an old session ends before the next one starts, and a `join` made in `sessionStarted` is never undone by an older session. Listeners added late get `sessionStarted` replayed for the live sessions, survive voice server restarts, and are removed through the returned handle; `GtnhVoiceApi.session(uuid)` and `sessions()` query the live sessions (server API 1.2)

### Changed

- Voice protocol bumped to v6 (the group table carries each group's internal name beside its display name): 0.10.0 clients and servers no longer talk to 0.9.x ones - the voice handshake rejects the version mismatch, so update both sides together
- A voice source's positional flag and position now apply when its frame plays rather than when the packet arrives, so a group switch no longer re-spatializes audio still in the jitter buffer; `sourceMetadata` follows the audio accordingly
- The group-membership wipe of a timed-out voice session runs on the server thread instead of the session reaper's thread, and the wipe of a logged-out session moves from the logout event to the end of that server tick, right before `sessionEnded`; `IGroup.onPlayerRemoved` is now always called on the server thread (group API 1.1)
- Voice volume no longer follows Minecraft's master sound slider (reverting the 0.8.0 coupling): the voice settings' master and per-player volumes alone decide how loud voice, addon sources and the mic monitor play, so turning game sound down or off leaves voice untouched

## [0.9.2] - 2026-10-03

### Fixed

- Open GUI screens (JourneyMap's fullscreen map, MUI2 screens, the voice settings screen) no longer render with a grey tint while the voice HUD is active: the HUD's MUI2 draw pass left `GL_LIGHTING`/item lighting enabled right before Minecraft drew the screen; GL state is now restored after every HUD frame

## [0.9.1] - 2026-09-27

### Fixed

- Addons depending on this mod through JitPack no longer crash dev environments with duplicate unimixins: JitPack stripped the `dev` classifiers from the published Gradle module metadata, so it is no longer published (the POM keeps them)

## [0.9.0] - 2026-09-27

### Added

- Addon-owned playback sources — `addon.source()...create()` returns an `IAddonSource` that plays addon-produced PCM (radios, jukeboxes) through the voice OpenAL context, positional or flat, paced by a blocking `write`

## [0.8.0] - 2026-07-11

### Security

- Encrypt voice with an ephemeral X25519 handshake (HKDF-SHA256 key, AES-GCM), replacing the leaked key sent in cleartext
- Bound client resources against malicious peers (roster, jitter buffer, voice sources, error logging)
- Cap length-prefixed fields in the voice handshake decode, so a single crafted hello can't force a multi-gigabyte server allocation
- Rate-limit the handshake log so a hello flood can't fill the server disk (the log now sits behind the handshake rate limiter)
- Rate-limit inbound voice audio per session, so one client can't flood the server or amplify its traffic onto other players
- Rate-limit all inbound voice UDP per session before decryption, so a ping flood can't pin the server's voice thread on wasted decrypts
- Throttle the UDP address-relearn log so a source-port-rotation flood can't fill the server disk
- Contain malformed voice frames in the decoder, so a crafted packet can't kill a listener's playback thread and mute a speaker
- Drop wrong-side control packets before they decode, so a client can't flood the server log with handshake-decode stack traces
- Drop replayed voice frames with a per-session sliding window, so a captured packet can't be re-injected to nearby players
- Clamp jitter-buffer scheduling against overflow, so a crafted audio sequence number can't permanently mute a speaker

### Fixed

- Per-player volume above 100% now actually boosts (OpenAL clamped it to 100% before)
- Native crash (double-free) when a voice source is torn down
- Voice sessions no longer linger as ghosts after a player times out

### Changed

- Lower CPU cost per voice frame (cached AES cipher)
- Fine-tuned adaptive jitter buffer parameters
- **Breaking**: client addons now register themselves first — `GtnhVoiceClient.addon("Name").description("...").register()` returns the handle that opens audio/capture bundles; addon names are unique
- Voice volume now follows Minecraft's master sound slider (effective volume = master × voice)
- Per-player volume/mute moved from its own screen into the settings screen's Output tab
- Denoise toggle now applies live mid-session (previously next voice connection)
- **Breaking**: server group API moves to multi-membership — `join`/`leave`/`groupsOf` replace `assign`/`groupOf`, groups gain `priority()`; `/voicegroup` becomes `/voicegroup <join|leave> global`
- Voice UDP protocol bumped to v5 (audio packets carry the routing group id)

### Removed

- Internal dead-code purge

### Added

- Multi-group voice membership: players can be in several groups at once; group priorities decide which one delivers audio to each listener (one packet per listener); the built-in global group overrides all others; a group can claim a frame exclusively (`RoutingContext.exclusive()`), skipping all lower-priority groups
- HUD speaker rows (and the self row) show which group the audio flows through; group changes animate (old name crumbles away, new one fades in)
- Tabbed voice settings screen (Output / Input / Addons) with a voice session status line
- Master voice volume (0-100%) and mic gain (0-200%) sliders
- Mic monitor on the Input tab: hear your own processed mic while other voices are muted
- Addons tab listing registered client addons (name + description)
- HUD dims while fresh chat messages are visible and fades out while another screen is open
- HUD: live audio-level bars with a gentle speaking pulse for yourself and every speaker; self row adds smooth mic-state color fades and a mute/unmute morph
- HUD rows animate in/out and arc between slots; a stopped speaker's row lingers 5s before leaving
- Fluent PCM filter chains for client addons (`chain(...)`/`playbackChain(...)`)
- Runtime filter enable/disable on `IRegistration` (`setFilterEnabled`), seedable via `initiallyEnabled(...)`

## [0.7.1] - 2026-07-06

### Added

- Client audio addons can request extra EFX auxiliary sends per source via `IAudioRegistrationBuilder.auxiliarySends(int)`; the host provisions the max across live registrations at context creation

## [0.7.0] - 2026-07-05

### Added

- Public client addon API (`com.enn3developer.gtnhvoice.api.client`): OpenAL context/source lifecycle events, audio-thread tick, playback/capture PCM filters, source position queries, `runOnAudioThread` — enough for EFX-style addons (e.g. sound physics)
- Releases now ship a separate `-api.jar`; also resolvable via JitPack

## [0.6.0] - 2026-07-05

### Added

- Dedicated addon API packages `com.enn3developer.gtnhvoice.api.server` (side-level types) and `com.enn3developer.gtnhvoice.api.server.group` (group routing surface), both FML `@API`, with `GtnhVoiceApi.groupManager()` as the entry point to the new `IGroupManager` interface
- Group routing API no longer leaks wire packet types: `RoutingContext.getAudio()` returns the new `IAudioFrame`, the positional/flat constants live on the new `SourceState`, and `sendTo` now takes a source state plus coordinates instead of a packet (also covers emitting from a fixed point, e.g. loudspeaker blocks); routing behavior unchanged
- Group voice chat foundations: per-group audio routing (proximity is the default group) and a per-packet positional/flat playback flag
- The HUD self row's group label is now synced from the server instead of hardcoded (still shows `[local]` for the default group); voice protocol bumped to v3
- Global announcement group: broadcasts the speaker to every voice client at full gain, regardless of group, dimension, or distance; switch with the op-only `/voicegroup <local|global>` command
- Group registry for third-party mods: implement `IGroup` and `registerGroup()` it on the `GroupManager` (re-register every server start); built-in names are reserved and `/voicegroup` stays limited to the built-ins
- Fluent recipient-selection API for group routing: `RoutingContext` now carries the whole routing event and groups chain filters like `getAllSessions().excludeSelf().excludeNoAddress().send(...)`; both built-ins rewritten on it, routing behavior unchanged
- Reusable filter vocabulary for group routing: `Filters.memberOf`/`Filters.player` for roster and whisper targeting, plus `RoutingContext.inDimension`/`withinDistanceOf` for fixed-point positional rules (radio towers, zones); all plug into `RecipientSelection.filter()`
- `IVoiceSession` read-only session view: the routing API (filters, session map, `sendTo`) now exposes only player identity and UDP reachability; credentials and transport state stay internal
- `RoutingContext.builder()` replaces the public constructor, chiefly for addon unit tests with a capturing `PacketSender`

### Removed

- Debug-era per-frame voice routing logs

### Changed

- The HUD moved to the bottom-left: your own row is now always pinned in the corner with a mic-state dot (green speaking, red muted, gray idle), other rows stack upward with slide/fade animations
- Playback command queue hardened ahead of a future `runOnAudioThread` addon API: a throwing command is isolated and logged instead of killing playback, and commands submitted while playback isn't running are rejected instead of queued forever

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

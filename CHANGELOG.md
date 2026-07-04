# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/2.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Server-synced voice roster (UUID -> player name) over the reliable control channel, sent as a full snapshot when a player's voice session is established and kept current via add/remove deltas as players join/leave voice; lets the client resolve a speaker's name from its UUID, which MC 1.7.10's tab list cannot provide on its own

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

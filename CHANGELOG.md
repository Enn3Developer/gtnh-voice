# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/2.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

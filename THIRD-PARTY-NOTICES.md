# Third-party notices

## Plasmo Voice

Portions of `com.enn3developer.gtnhvoice.core` (protocol packet definitions, packet
utilities, codec/encryption value objects, encoder/decoder/encryption interfaces, audio
math utilities, the AES encryption implementation, the pure-Java and native Opus codec
wrappers and the native-vs-Java codec supplier, the adaptive jitter buffer, the
RNNoise-backed noise suppression filter, and the Netty-based UDP transport layer) are
adapted from [Plasmo Voice](https://github.com/plasmoapp/plasmo-voice), copyright the
Plasmo Voice contributors, licensed under the GNU Lesser General Public License v3.0
(LGPL-3.0).

A copy of the LGPL-3.0 license text is included in this repository's `LICENSE` file.

## Concentus

This mod bundles [Concentus](https://github.com/lostromb/concentus), a pure Java port of
the Opus audio codec, copyright Skype Limited, Xiph.Org Foundation, CSIRO, Microsoft
Corporation, Jean-Marc Valin, Gregory Maxwell, Mark Borgerding, Timothy B. Terriberry, and
Logan Stromberg. It is distributed under a BSD-style license (the same terms as the
reference Opus implementation); see the upstream `LICENSE` file for the full text.

## opus-jni-rust

This mod optionally bundles and loads
[opus-jni-rust](https://github.com/plasmoapp/opus-jni-rust), a native (JNI + Rust) Opus
codec binding, copyright the Plasmo Voice contributors, licensed under the MIT License.
`NativeOpusEncoder`/`NativeOpusDecoder` (see above) wrap this library; `OpusCodecSupplier`
uses it only as an optional acceleration and always falls back to the pure-Java Concentus
codec above if it isn't available or fails to load.

## rnnoise-jni-rust

This mod optionally bundles and loads
[rnnoise-jni-rust](https://github.com/plasmoapp/rnnoise-jni-rust), a native (JNI + Rust)
RNNoise noise suppression binding, copyright the Plasmo Voice contributors, licensed
under the MIT License. `NoiseSuppressionFilter` (see above) wraps this library;
`NoiseSuppressionFilterSupplier` uses it only as an optional capture-side filter and
skips denoising entirely (sending captured audio unprocessed) if it isn't available or
fails to load - unlike Opus, there is no pure-Java fallback for RNNoise.

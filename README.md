# GTNH Voice
A voice chat mod for GTNH (based on [Plasmo](https://github.com/plasmoapp/plasmo-voice))

## AI Usage Disclaimer

Generative AI (LLM) was used in developing this mod, every AI contribution was marked as such
in corresponding commits.

If you're turned off by this, then please skip over this mod.

## How it works

This mod needs to be installed both on the server and on the client in order to work properly.

When the client logs in the server, they do a handshake to initialize a separate UDP connection where voice packets
are sent through.
In the handshake, the server sends to the client parameters like the host and port to connect to, so every server can
configure them as they want (default: same host, 25566).

Once connected, the client can start sending voice packets and receive them from the server.

As for the internal packet decoding buffer, it uses the same one from Plasmo, aka AdaptiveJitterBuffer, to
automatically adjust the buffer length based on how good the connection with the server is.

An optional feature, enabled by default, is the denoiser (same RNNoise from Plasmo).

The client uses OpenAL with 3D sources to enable positional audio (best results when HRTF is enabled) and should
work with [Sound Physics Mod](https://github.com/mist475/Sound-Physics) (never tested, that mod uses mixins to inject itself
in OpenAL sources).

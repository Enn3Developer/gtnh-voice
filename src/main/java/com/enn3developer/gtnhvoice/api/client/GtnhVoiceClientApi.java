package com.enn3developer.gtnhvoice.api.client;

import com.enn3developer.gtnhvoice.client.api.ClientApiBackend;

/**
 * Static entry point addons use to reach the voice client's audio and capture hooks - the single place this
 * package touches the client internals, so addons never import them. Client side only, like everything in this
 * package - it exists on clients (including the client half of an integrated server), never on a dedicated
 * server.
 */
public final class GtnhVoiceClientApi {

    private GtnhVoiceClientApi() {}

    /**
     * The playback-side {@link IClientAudioApi}: lifecycle/filter registration bundles, audio-thread
     * marshalling and source-metadata queries. Client side only.
     */
    public static IClientAudioApi audio() {
        return ClientApiBackend.getInstance()
            .audio();
    }

    /**
     * The capture-side {@link IClientCaptureApi}: PCM filter registration bundles on outgoing mic audio.
     * Client side only.
     */
    public static IClientCaptureApi capture() {
        return ClientApiBackend.getInstance()
            .capture();
    }
}

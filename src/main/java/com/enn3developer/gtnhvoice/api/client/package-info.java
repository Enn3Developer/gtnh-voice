/**
 * The addon integration surface for client-side voice: OpenAL context/source lifecycle hooks, PCM filters on
 * incoming (playback) and outgoing (capture) audio, spatial source-metadata queries and audio-thread marshalling,
 * all reached through {@link com.enn3developer.gtnhvoice.api.client.GtnhVoiceClientApi}, the client API entry
 * point. Client side only, like everything in this package.
 */
@API(owner = "gtnhvoice", apiVersion = "1.0", provides = "GtnhVoice|ClientAPI")
package com.enn3developer.gtnhvoice.api.client;

import cpw.mods.fml.common.API;

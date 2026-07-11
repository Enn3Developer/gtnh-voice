/**
 * The addon integration surface for client-side voice: OpenAL context/source lifecycle hooks, PCM filters on
 * incoming (playback) and outgoing (capture) audio, spatial source-metadata queries and audio-thread marshalling.
 * Everything starts at {@link com.enn3developer.gtnhvoice.api.client.GtnhVoiceClient#addon}: register the addon
 * once, then reach the whole surface through the returned
 * {@link com.enn3developer.gtnhvoice.api.client.IVoiceAddon} handle. Client side only, like everything in this
 * package.
 */
@API(owner = "gtnhvoice", apiVersion = "2.0", provides = "GtnhVoice|ClientAPI")
package com.enn3developer.gtnhvoice.api.client;

import cpw.mods.fml.common.API;

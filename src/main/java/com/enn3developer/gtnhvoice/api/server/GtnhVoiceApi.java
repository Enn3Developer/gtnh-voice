package com.enn3developer.gtnhvoice.api.server;

import com.enn3developer.gtnhvoice.api.server.group.IGroupManager;
import com.enn3developer.gtnhvoice.server.VoiceServerManager;

/**
 * Static entry point addons use to reach the voice server's group routing - the single place this package touches
 * the server internals, so addons never import them. Server side only, like everything in this package.
 */
public final class GtnhVoiceApi {

    private GtnhVoiceApi() {}

    /**
     * The live {@link IGroupManager}: register groups and drive membership through it. Server side only - it
     * exists on dedicated and integrated servers, never on a pure client.
     */
    public static IGroupManager groupManager() {
        return VoiceServerManager.getInstance()
            .getGroupManager();
    }
}

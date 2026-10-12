package com.enn3developer.gtnhvoice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.client.source.TestLanguage;
import com.enn3developer.gtnhvoice.network.VoiceGroupUpdatePacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

/**
 * The HUD self row's group label, as {@link VoiceClientManager} keeps it from {@link VoiceGroupUpdatePacket}s: the
 * group's display component in this client's language when the server sent one, its plain display name otherwise,
 * and "local" again once the session ends. The manager is a fresh one that never connects.
 */
class SelfGroupDisplayNameTest {

    private static final String CALL_KEY = "gtnhvoice.test.selfGroup.call";

    private final VoiceClientManager manager = new VoiceClientManager();

    @Test
    void showsTheGroupsDisplayComponentInTheClientsLanguage() {
        String call = IChatComponent.Serializer.func_150696_a(new ChatComponentTranslation(CALL_KEY));
        TestLanguage.translate(CALL_KEY, "Chiamata");

        manager.handleGroupUpdate(new VoiceGroupUpdatePacket(VoiceProtocol.PROTOCOL_VERSION, "Call", call));

        assertEquals("Chiamata", manager.getGroupDisplayName());
        TestLanguage.translate(CALL_KEY, "Call");
        assertEquals("Call", manager.getGroupDisplayName(), "and follows a language change");
    }

    @Test
    void showsThePlainDisplayNameOfAGroupWithoutAComponent() {
        manager.handleGroupUpdate(new VoiceGroupUpdatePacket(VoiceProtocol.PROTOCOL_VERSION, "Squad 7"));

        assertEquals("Squad 7", manager.getGroupDisplayName());
    }

    @Test
    void goesBackToLocalWhenTheSessionEnds() {
        manager.handleGroupUpdate(new VoiceGroupUpdatePacket(VoiceProtocol.PROTOCOL_VERSION, "Squad 7"));

        manager.onDisconnected();

        assertEquals("local", manager.getGroupDisplayName());
    }
}

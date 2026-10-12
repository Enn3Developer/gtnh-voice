package com.enn3developer.gtnhvoice.client.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import org.junit.jupiter.api.Test;

/**
 * {@link GroupDisplayName}: a group's display component, as the server serializes it, shows in this client's language
 * and follows a language change; the plain display name shows whenever the component can't. Languages are played by
 * {@link TestLanguage}.
 */
class GroupDisplayNameTest {

    private static final String CALL_KEY = "gtnhvoice.test.groupDisplayName.call";
    private static final String RADIO_KEY = "gtnhvoice.test.groupDisplayName.radio";

    @Test
    void aGroupWithoutAComponentShowsItsPlainDisplayName() {
        assertEquals(
            "Call",
            GroupDisplayName.of("Call", null)
                .text());
    }

    @Test
    void aTranslatedNameShowsInTheClientsLanguageAndFollowsALanguageChange() {
        GroupDisplayName name = GroupDisplayName.of("Call", json(new ChatComponentTranslation(CALL_KEY)));

        TestLanguage.translate(CALL_KEY, "Call");
        assertEquals("Call", name.text(), "an English client");

        TestLanguage.translate(CALL_KEY, "Chiamata");
        assertEquals("Chiamata", name.text(), "the same client, switched to Italian");
    }

    @Test
    void translationArgumentsAreFormattedIn() {
        TestLanguage.translate(RADIO_KEY, "Canale radio %s");

        GroupDisplayName name = GroupDisplayName.of("Radio 3", json(new ChatComponentTranslation(RADIO_KEY, "3")));

        assertEquals("Canale radio 3", name.text());
    }

    @Test
    void aTextComponentShowsItsText() {
        GroupDisplayName name = GroupDisplayName.of("plain", json(new ChatComponentText("Squad 7")));

        assertEquals("Squad 7", name.text());
    }

    @Test
    void aKeyThisClientCannotTranslateShowsThePlainDisplayNameInsteadOfTheKey() {
        GroupDisplayName name = GroupDisplayName
            .of("Call", json(new ChatComponentTranslation("gtnhvoice.test.groupDisplayName.untranslated")));

        assertEquals("Call", name.text());
    }

    @Test
    void aComponentThatDoesNotParseShowsThePlainDisplayName() {
        assertEquals(
            "Call",
            GroupDisplayName.of("Call", "{\"translate\":")
                .text());
        assertEquals(
            "Call",
            GroupDisplayName.of("Call", "{\"neither\":\"text nor translate\"}")
                .text());
    }

    private static String json(IChatComponent component) {
        return IChatComponent.Serializer.func_150696_a(component);
    }
}

package com.enn3developer.gtnhvoice.client.source;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import net.minecraft.util.StatCollector;
import net.minecraft.util.StringTranslate;

/**
 * Plays the client's language in tests: sets translations in Minecraft's real translation table, the one
 * {@code ChatComponentTranslation} and {@code StatCollector} read, as a language change or a mod's lang file would.
 * Keys are only ever added or overwritten, never removed, so tests use keys of their own ({@code gtnhvoice.test.*}).
 */
public final class TestLanguage {

    private TestLanguage() {}

    /**
     * Sets {@code key} to translate to {@code text}, as switching to a language with that translation does. The
     * table stamps every change with the current millisecond, and that stamp is how translations notice a language
     * change, so this first waits for the clock to pass the last change.
     */
    public static void translate(String key, String text) {
        long lastChange = StatCollector.getLastTranslationUpdateTimeInMilliseconds();
        while (System.currentTimeMillis() <= lastChange) {
            Thread.onSpinWait();
        }
        StringTranslate.inject(new ByteArrayInputStream((key + "=" + text + "\n").getBytes(StandardCharsets.UTF_8)));
    }
}

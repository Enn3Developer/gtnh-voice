package com.enn3developer.gtnhvoice.client.source;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * One voice group's display name as this client shows it: the group's display component (the server-side
 * {@code IGroup#getDisplayComponent()}, sent as JSON) formatted in this client's language, or else the plain display
 * name the server sends beside it. The plain name wins when the group has no component, when the JSON doesn't parse,
 * when this client has no translation for the component's translation key (the addon's lang files are missing here),
 * and when formatting fails.
 * <p>
 * The text follows the client's language: it is formatted once per language and cached, and the first read after a
 * language change formats it again. Thread-safe: the source decode threads read it per frame, the HUD per render frame.
 */
public final class GroupDisplayName {

    private static final long PARSE_FAILURE_LOG_THROTTLE_MILLIS = 5000;
    private static final AtomicLong lastParseFailureLogMillis = new AtomicLong();

    private final String plain;
    private final @Nullable IChatComponent component;

    // The component's text in the language it was last formatted for, which is identified by the update time of the
    // translation table. Guarded by this, which also keeps the (mutable) component on one thread at a time.
    private String formatted;
    private long formattedFor;

    private GroupDisplayName(@NotNull String plain, @Nullable IChatComponent component) {
        this.plain = plain;
        this.component = component;
    }

    /**
     * The display name of a group the server sent as {@code displayName} and, unless {@code null},
     * {@code componentJson} - see the class doc for which one shows. Any thread.
     */
    public static @NotNull GroupDisplayName of(@NotNull String displayName, @Nullable String componentJson) {
        if (componentJson == null) return new GroupDisplayName(displayName, null);

        try {
            return new GroupDisplayName(displayName, IChatComponent.Serializer.func_150699_a(componentJson));
        } catch (RuntimeException e) {
            if (LogThrottle.shouldLog(lastParseFailureLogMillis, PARSE_FAILURE_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.warn(
                    "Showing voice group '{}' by its plain name: its display component does not parse",
                    displayName,
                    e);
            }
            return new GroupDisplayName(displayName, null);
        }
    }

    /** The text to show, in this client's current language. Any thread. */
    public @NotNull String text() {
        if (component == null) return plain;

        long language = StatCollector.getLastTranslationUpdateTimeInMilliseconds();
        synchronized (this) {
            if (formatted == null || formattedFor != language) {
                formatted = format(component);
                formattedFor = language;
            }
            return formatted;
        }
    }

    private String format(IChatComponent component) {
        try {
            if (component instanceof ChatComponentTranslation translation
                && !StatCollector.canTranslate(translation.getKey())) return plain;
            return component.getUnformattedText();
        } catch (RuntimeException e) {
            // A translation that formats with neither this client's lang files nor the fallback ones.
            return plain;
        }
    }

    @Override
    public String toString() {
        return text();
    }
}

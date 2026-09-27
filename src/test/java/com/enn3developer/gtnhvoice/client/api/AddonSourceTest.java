package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IAddonSource;
import com.enn3developer.gtnhvoice.api.client.IAddonSourceBuilder;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * Builder validation and the no-session contract of {@link AddonSource}. Like {@code VoiceAddonNoSessionTest},
 * the real {@code VoiceClientManager} singleton has never connected in this JVM, which is exactly the documented
 * no-session state: writes return {@code false} without blocking and the queries report nothing live.
 */
class AddonSourceTest {

    private final TestAddons addons = new TestAddons(new ClientApiBackend());

    private IAddonSourceBuilder builder() {
        return addons.addon("source-test")
            .source();
    }

    @Test
    void builderRejectsOutOfRangeArguments() {
        assertThrows(IllegalArgumentException.class, () -> builder().distance(0));
        assertThrows(IllegalArgumentException.class, () -> builder().gain(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> builder().gain(2.1f));
        assertThrows(IllegalArgumentException.class, () -> builder().gain(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> builder().bufferMillis(39));
        assertThrows(IllegalArgumentException.class, () -> builder().bufferMillis(1001));
    }

    @Test
    void builderIsSingleUse() {
        IAddonSourceBuilder builder = builder();
        builder.create();
        assertThrows(IllegalStateException.class, builder::create);
        assertThrows(IllegalStateException.class, () -> builder.gain(1f));
    }

    @Test
    void everySourceGetsItsOwnId() {
        assertNotEquals(
            builder().create()
                .id(),
            builder().create()
                .id());
    }

    @Test
    void writeReturnsFalseWithoutASession() throws InterruptedException {
        IAddonSource source = builder().create();
        assertFalse(source.write(new short[VoiceFormat.FRAME_SAMPLES]));
        assertFalse(source.isLive());
        assertEquals(0, source.bufferedFrames());
    }

    @Test
    void writeRejectsWrongFrameLength() {
        IAddonSource source = builder().create();
        assertThrows(IllegalArgumentException.class, () -> source.write(new short[VoiceFormat.FRAME_SAMPLES - 1]));
        assertThrows(NullPointerException.class, () -> source.write(null));
    }

    @Test
    void writeAfterCloseThrowsAndCloseIsIdempotent() {
        IAddonSource source = builder().create();
        source.close();
        assertDoesNotThrow(source::close);
        assertThrows(IllegalStateException.class, () -> source.write(new short[VoiceFormat.FRAME_SAMPLES]));
    }

    @Test
    void settersWorkWithoutASession() {
        IAddonSource source = builder().create();
        assertDoesNotThrow(() -> {
            source.setPosition(1, 2, 3);
            source.setPositional(false);
            source.setGain(0.5f);
            source.flush();
        });
        assertThrows(IllegalArgumentException.class, () -> source.setGain(3f));
    }
}

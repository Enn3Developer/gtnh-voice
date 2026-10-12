package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;
import com.enn3developer.gtnhvoice.network.GroupNames;

/**
 * Where a playback frame plays from: a positional frame keeps the position it was built with, a flat frame has none
 * (NaN) whatever it was built with, and frames from code that predates positions, the 0.10.1 constructor and factory
 * or an {@link IPlaybackFrame} implementation of its own, read NaN.
 */
class PlaybackFrameTest {

    private static final UUID SPEAKER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final GroupNames RADIO = new GroupNames("radio", "Radio");

    @Test
    void positionalFrameKeepsItsPosition() {
        PlaybackFrame frame = PlaybackFrame.of(SPEAKER, RADIO, true, 1.5, 64, -3);

        assertEquals(List.of(1.5, 64.0, -3.0), position(frame));
    }

    @Test
    void flatFrameHasNoPositionWhateverItWasBuiltWith() {
        PlaybackFrame frame = PlaybackFrame.of(SPEAKER, RADIO, false, 1.5, 64, -3);

        assertEquals(List.of(Double.NaN, Double.NaN, Double.NaN), position(frame));
    }

    @Test
    void theConstructorAndFactoryFrom0101StillWorkAndLeaveThePositionNaN() {
        PlaybackFrame constructed = new PlaybackFrame(SPEAKER, "radio", "Radio", true);
        PlaybackFrame made = PlaybackFrame.of(SPEAKER, RADIO, true);

        assertEquals(List.of(Double.NaN, Double.NaN, Double.NaN), position(constructed));
        assertEquals(constructed, made);
    }

    @Test
    void anImplementationWrittenBeforeClientApi24ReadsNaN() {
        IPlaybackFrame legacy = new IPlaybackFrame() {

            @Override
            public UUID sourceId() {
                return SPEAKER;
            }

            @Override
            public String groupName() {
                return "local";
            }

            @Override
            public String groupDisplayName() {
                return "local";
            }

            @Override
            public boolean positional() {
                return true;
            }
        };

        assertEquals(List.of(Double.NaN, Double.NaN, Double.NaN), position(legacy));
    }

    private static List<Double> position(IPlaybackFrame frame) {
        return List.of(frame.x(), frame.y(), frame.z());
    }
}

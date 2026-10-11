package com.enn3developer.gtnhvoice.client.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioDecoder;
import com.enn3developer.gtnhvoice.network.GroupNames;

/**
 * Drives one {@link VoiceSource} through its jitter buffer with a fake clock, stepping the poller by hand
 * ({@link VoiceSource#emitNextFrame()}) instead of racing its thread, and records what reaches playback: which
 * frame played, with which {@link IPlaybackFrame} context, and when the routing (positional flag, position) was
 * handed to the AL source. The decoder writes each packet's sequence number into the PCM so a played frame can be
 * matched to the packet it came from; concealment frames come out as {@code PLC}.
 * <p>
 * The arrival timelines are chosen by hand against the jitter schedule (20ms slots, a 20ms floor, the initial
 * 80ms adaptive delay collapsing to 0 once a second packet shows near-cadence arrival); the comments next to each
 * timeline say which slot comes due when.
 */
class VoiceSourcePlayoutTest {

    private static final UUID SPEAKER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final long START_MILLIS = 1_000L;
    private static final long STEP_MILLIS = 5L;
    private static final short LOCAL = 0;
    private static final short ALPHA = 2;
    private static final short BRAVO = 3;
    private static final short CHARLIE = 4;

    /** Records the routing hand-off and every submitted frame, in call order; touches no OpenAL. */
    private static final class RecordingPlaybackManager extends PlaybackManager {

        final List<String> events = new ArrayList<>();
        final List<IPlaybackFrame> playedContexts = new ArrayList<>();

        @Override
        public void start(String deviceName, Config.HrtfMode hrtfMode) {}

        @Override
        public void stop() {}

        @Override
        public void createSource(UUID sourceId, int distance, float gain) {}

        @Override
        public void destroySource(UUID sourceId) {}

        @Override
        public void resetSource(UUID sourceId) {}

        @Override
        public void setPositional(UUID sourceId, boolean positional) {
            events.add("positional:" + positional);
        }

        @Override
        public void updateSourcePosition(UUID sourceId, double x, double y, double z) {
            events.add("position:" + x + "," + y + "," + z);
        }

        @Override
        public void submit(IPlaybackFrame frame, short[] pcm) {
            String audio = pcm[0] == PLC_MARKER ? "PLC" : "seq" + pcm[0];
            events.add("play:" + audio + "/" + frame.groupName());
            playedContexts.add(frame);
        }

        List<String> plays() {
            List<String> plays = new ArrayList<>();
            for (String event : events) {
                if (event.startsWith("play:")) plays.add(event.substring("play:".length()));
            }
            return plays;
        }
    }

    private static final short PLC_MARKER = -1;

    /** Decodes a packet into a frame filled with its first byte (the sequence number); concealment into -1s. */
    private static final class MarkingDecoder implements AudioDecoder {

        @Override
        public short[] decode(byte[] encoded) {
            short[] frame = new short[960];
            Arrays.fill(frame, encoded == null ? PLC_MARKER : encoded[0]);
            return frame;
        }

        @Override
        public void open() {}

        @Override
        public void reset() {}

        @Override
        public void close() {}

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    /** One source on a fake clock plus the arrivals scheduled against that clock. */
    private static final class Harness {

        final long[] now = { START_MILLIS };
        final RecordingPlaybackManager playback = new RecordingPlaybackManager();
        final GroupTable groups = new GroupTable();
        final VoiceSource source = new VoiceSource(SPEAKER, playback, (sr, st, fs) -> new MarkingDecoder(), groups,
            () -> now[0]);
        final Map<Long, List<Runnable>> arrivals = new TreeMap<>();

        Harness() throws Exception {
            groups.replace(
                Map.of(
                    LOCAL,
                    new GroupNames("local", "local"),
                    ALPHA,
                    new GroupNames("alpha", "Alpha"),
                    BRAVO,
                    new GroupNames("bravo", "Bravo"),
                    CHARLIE,
                    new GroupNames("charlie", "Charlie")));
            source.open(16);
        }

        /** Packet {@code sequence} routed by {@code groupId} arrives {@code offsetMillis} after the start. */
        void arrives(long offsetMillis, int sequence, short groupId) {
            arrives(offsetMillis, sequence, groupId, true, 0, 0, 0);
        }

        void arrives(long offsetMillis, int sequence, short groupId, boolean positional, double x, double y,
            double z) {
            arrivals.computeIfAbsent(START_MILLIS + offsetMillis, t -> new ArrayList<>())
                .add(
                    () -> source
                        .handleAudio(sequence, new byte[] { (byte) sequence }, x, y, z, positional, groupId));
        }

        /** Steps the clock to {@code offsetMillis}: at each step, that instant's arrivals, then a full drain. */
        void runUntil(long offsetMillis) {
            for (; now[0] <= START_MILLIS + offsetMillis; now[0] += STEP_MILLIS) {
                arrivals.getOrDefault(now[0], List.of())
                    .forEach(Runnable::run);
                while (source.emitNextFrame()) {
                    // drain everything due at this instant, exactly like the poller does before blocking
                }
            }
        }
    }

    @Test
    void framesFromDifferentGroupsArrivingOutOfOrderUnderJitterEachPlayWithTheirOwnGroup() throws Exception {
        Harness harness = new Harness();
        // seq2 overtakes seq1, seq5 overtakes seq4, arrival spacing jitters between 5 and 30ms. Once seq2 lands the
        // delay collapses to the 20ms floor, so slot n plays at start + 20(n+1).
        harness.arrives(0, 0, ALPHA);
        harness.arrives(30, 2, CHARLIE);
        harness.arrives(35, 1, BRAVO);
        harness.arrives(60, 3, ALPHA);
        harness.arrives(75, 5, BRAVO);
        harness.arrives(80, 4, LOCAL);

        harness.runUntil(200);

        assertEquals(
            List.of("seq0/alpha", "seq1/bravo", "seq2/charlie", "seq3/alpha", "seq4/local", "seq5/bravo"),
            harness.playback.plays());
    }

    @Test
    void concealmentFrameCarriesTheGroupOfTheLastRealFrameNotTheNextOne() throws Exception {
        Harness harness = new Harness();
        // seq1 is lost. seq2 lands at +40, collapsing the delay: seq0 is already due, slot 1 is overdue right then
        // and seq2 is not due until +60 - the window in which the gap is concealed.
        harness.arrives(0, 0, ALPHA);
        harness.arrives(40, 2, BRAVO);

        harness.runUntil(100);

        assertEquals(List.of("seq0/alpha", "PLC/alpha", "seq2/bravo"), harness.playback.plays());
    }

    @Test
    void routingReachesTheAlSourceWhenItsFramePlaysNotWhenANewerPacketArrives() throws Exception {
        Harness harness = new Harness();
        // seq1 (flat, new position) arrives at +20 while seq0 (positional) is still waiting for its slot.
        harness.arrives(0, 0, ALPHA, true, 1.5, 64.0, -3.25);
        harness.arrives(20, 1, BRAVO, false, 2.0, 70.0, 8.0);

        harness.runUntil(20);
        List<String> afterSeq1Arrived = new ArrayList<>(harness.playback.events);
        harness.runUntil(40);

        assertEquals(
            List.of("positional:true", "position:1.5,64.0,-3.25", "play:seq0/alpha"),
            afterSeq1Arrived,
            "seq1's arrival must not touch the AL source before seq0 has played with its own routing");
        assertEquals(
            List.of(
                "positional:true",
                "position:1.5,64.0,-3.25",
                "play:seq0/alpha",
                "positional:false",
                "position:2.0,70.0,8.0",
                "play:seq1/bravo"),
            harness.playback.events);
    }

    @Test
    void frameRoutedByAGroupTheTableDoesNotKnowPlaysAsLocal() throws Exception {
        Harness harness = new Harness();
        short unannounced = 9;
        harness.arrives(0, 0, unannounced);

        harness.runUntil(100);

        IPlaybackFrame played = harness.playback.playedContexts.get(0);
        assertEquals("local", played.groupName(), "unattributable frames resolve to the local group");
        assertEquals("local", played.groupDisplayName());
    }

    @Test
    void frameContextCarriesTheSpeakerAndTheFramesOwnPositionalFlag() throws Exception {
        Harness harness = new Harness();
        harness.arrives(0, 0, CHARLIE, false, 0, 0, 0);

        harness.runUntil(100);

        IPlaybackFrame played = harness.playback.playedContexts.get(0);
        assertEquals(SPEAKER, played.sourceId());
        assertEquals("Charlie", played.groupDisplayName());
        assertFalse(played.positional(), "the flat frame's own flag, not the source's default");
    }
}

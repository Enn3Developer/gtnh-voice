package com.enn3developer.gtnhvoice.client.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioDecoder;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;

class VoiceSourceLifecycleTest {

    /** No-op stand-in so nothing touches OpenAL during unit tests. */
    static class NoOpPlaybackManager extends PlaybackManager {

        @Override
        public void start(String deviceName, com.enn3developer.gtnhvoice.Config.HrtfMode hrtfMode) {}

        @Override
        public void stop() {}

        @Override
        public void createSource(UUID sourceId, int distance, float gain) {}

        @Override
        public void destroySource(UUID sourceId) {}

        @Override
        public void resetSource(UUID sourceId) {}

        @Override
        public void setPositional(UUID sourceId, boolean positional) {}

        @Override
        public void updateSourcePosition(UUID sourceId, double x, double y, double z) {}

        @Override
        public void submit(UUID sourceId, short[] frame) {}

        @Override
        public void updateListener(double x, double y, double z, float lookX, float lookY, float lookZ) {}
    }

    /** Counts close() calls and hands back silent frames without any native codec. */
    static class CountingDecoder implements AudioDecoder {

        private final AtomicInteger closeCount = new AtomicInteger();

        int getCloseCount() {
            return closeCount.get();
        }

        @Override
        public short[] decode(byte[] encoded) {
            return new short[960];
        }

        @Override
        public void open() {}

        @Override
        public void reset() {}

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    @Test
    void audioAfterStopCreatesNothing() {
        AtomicInteger created = new AtomicInteger();
        DecoderFactory factory = (sr, st, fs) -> {
            created.incrementAndGet();
            return new CountingDecoder();
        };

        VoiceSourceManager mgr = new VoiceSourceManager(new NoOpPlaybackManager(), factory, id -> Optional.of("test"));
        mgr.start();
        mgr.stop();

        SourceAudioPacket packet = new SourceAudioPacket(
            0L,
            SourceAudioPacket.STATE_POSITIONAL,
            new byte[] { 1, 2, 3, 4 },
            UUID.randomUUID(),
            1.0,
            2.0,
            3.0,
            (short) 0);
        mgr.onSourceAudio(packet, 16);

        assertEquals(0, created.get());
    }

    @Test
    void audioForNonRosterSourceCreatesNothing() {
        AtomicInteger created = new AtomicInteger();
        DecoderFactory factory = (sr, st, fs) -> {
            created.incrementAndGet();
            return new CountingDecoder();
        };

        VoiceSourceManager mgr = new VoiceSourceManager(new NoOpPlaybackManager(), factory, id -> Optional.empty());
        mgr.start();

        SourceAudioPacket packet = new SourceAudioPacket(
            0L,
            SourceAudioPacket.STATE_POSITIONAL,
            new byte[] { 1, 2, 3, 4 },
            UUID.randomUUID(),
            1.0,
            2.0,
            3.0,
            (short) 0);
        mgr.onSourceAudio(packet, 16);

        assertEquals(0, created.get());
    }

    @Test
    void resurrectionAfterRemovalBlockedByRoster() {
        AtomicInteger created = new AtomicInteger();
        DecoderFactory factory = (sr, st, fs) -> {
            created.incrementAndGet();
            return new CountingDecoder();
        };

        Set<UUID> present = ConcurrentHashMap.newKeySet();
        VoiceSourceManager mgr = new VoiceSourceManager(
            new NoOpPlaybackManager(),
            factory,
            id -> present.contains(id) ? Optional.of("x") : Optional.empty());

        UUID id = UUID.randomUUID();
        present.add(id);
        mgr.start();

        SourceAudioPacket packet = new SourceAudioPacket(
            0L,
            SourceAudioPacket.STATE_POSITIONAL,
            new byte[] { 1, 2, 3, 4 },
            id,
            1.0,
            2.0,
            3.0,
            (short) 0);
        mgr.onSourceAudio(packet, 16);
        assertEquals(1, created.get());

        mgr.removeSource(id);
        present.remove(id);

        mgr.onSourceAudio(packet, 16);
        assertEquals(1, created.get());
    }

    @Test
    void capBoundsConcurrentSources() {
        AtomicInteger created = new AtomicInteger();
        DecoderFactory factory = (sr, st, fs) -> {
            created.incrementAndGet();
            return new CountingDecoder();
        };

        VoiceSourceManager mgr = new VoiceSourceManager(new NoOpPlaybackManager(), factory, id -> Optional.of("x"));
        mgr.start();

        for (int i = 0; i < 65; i++) {
            UUID id = UUID.randomUUID();
            SourceAudioPacket packet = new SourceAudioPacket(
                0L,
                SourceAudioPacket.STATE_POSITIONAL,
                new byte[] { 1, 2, 3, 4 },
                id,
                1.0,
                2.0,
                3.0,
                (short) 0);
            mgr.onSourceAudio(packet, 16);
        }

        assertEquals(64, created.get());
        mgr.stop();
    }

    @Test
    void twoDestroysCloseDecoderOnce() throws Exception {
        CountingDecoder dec = new CountingDecoder();
        VoiceSource src = new VoiceSource(UUID.randomUUID(), new NoOpPlaybackManager(), (sr, st, fs) -> dec);
        src.create(16);
        src.destroy();
        src.destroy();

        assertEquals(1, dec.getCloseCount());
    }
}

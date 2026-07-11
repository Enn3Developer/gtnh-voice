package com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

/**
 * Wire round-trip of the sourceState flat flag: each packet carries its own mode, so a positional and a flat
 * frame from the same source must decode independently - that per-packet independence is what lets a speaker
 * switch groups mid-stream without the client tearing the source down. Positional must be the zero byte, since
 * that's what legacy peers always sent - a version-skewed pairing has to degrade to proximity, not flat.
 */
class SourceAudioPacketTest {

    @Test
    void positionalFlagRoundTripsPerPacket() throws IOException {
        UUID sourceId = UUID.randomUUID();

        SourceAudioPacket positional = roundTrip(
            new SourceAudioPacket(
                7L,
                SourceAudioPacket.STATE_POSITIONAL,
                new byte[] { 1, 2, 3 },
                sourceId,
                1.0,
                2.0,
                3.0,
                (short) 3));
        assertTrue(positional.isPositional());
        assertEquals(sourceId, positional.getSourceId());
        assertEquals(7L, positional.getSequenceNumber());
        assertEquals((short) 3, positional.getGroupId());

        SourceAudioPacket flat = roundTrip(
            new SourceAudioPacket(
                8L,
                SourceAudioPacket.FLAG_FLAT,
                new byte[] { 4, 5, 6 },
                sourceId,
                1.0,
                2.0,
                3.0,
                (short) 0));
        assertFalse(flat.isPositional());
        assertEquals((short) 0, flat.getGroupId());
    }

    @Test
    void legacyZeroSourceStateMeansPositional() {
        assertEquals(0, SourceAudioPacket.STATE_POSITIONAL);
        assertTrue(
            new SourceAudioPacket(1L, (byte) 0, new byte[] { 1 }, UUID.randomUUID(), 0, 0, 0, (short) 0)
                .isPositional(),
            "sourceState=0 is what pre-flag peers send on every packet - it must keep meaning positional");
    }

    private static SourceAudioPacket roundTrip(SourceAudioPacket packet) throws IOException {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        packet.write(out);

        SourceAudioPacket decoded = new SourceAudioPacket();
        decoded.read(ByteStreams.newDataInput(out.toByteArray()));
        return decoded;
    }
}

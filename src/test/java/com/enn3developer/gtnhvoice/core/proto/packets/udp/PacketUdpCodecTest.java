package com.enn3developer.gtnhvoice.core.proto.packets.udp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketDirection;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.CustomPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SelfAudioInfoPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceEndPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.google.common.io.ByteStreams;

/**
 * Wire-protocol round-trip and robustness coverage for {@link PacketUdpCodec}: every registered
 * packet type must survive encode -> decode with the correct secret and concrete type, and a
 * truncated or mis-magic'd buffer must decode to {@link Optional#empty()} without throwing.
 */
class PacketUdpCodecTest {

    private static final int MAGIC_NUMBER = 0x4e9004e9;

    @Test
    void everyRegisteredPacketRoundTrips() throws Exception {
        UUID secret = UUID.randomUUID();
        AesEncryption enc = new AesEncryption(new byte[32]);

        byte[] audio = { 1, 2, 3, 4, 5, 6, 7, 8 };
        byte[] custom = { 9, 8, 7, 6 };

        List<Packet<?>> packets = Arrays.asList(
            new PingPacket("127.0.0.1", 24454),
            new PlayerAudioPacket(42L, audio, UUID.randomUUID(), (short) 16, false),
            new SourceAudioPacket(7L, SourceAudioPacket.STATE_POSITIONAL, audio, UUID.randomUUID(), 1.0, 2.0, 3.0),
            new SelfAudioInfoPacket(UUID.randomUUID(), 3L, audio, (short) 16),
            new SourceEndPacket(UUID.randomUUID()),
            new CustomPacket("test", custom));

        for (Packet<?> pkt : packets) {
            byte[] b = PacketUdpCodec.encode(pkt, secret, enc);
            assertTrue(b != null, () -> "encode returned null for " + pkt.getClass().getSimpleName());

            Optional<PacketUdp> out = PacketUdpCodec.decode(ByteStreams.newDataInput(b), PacketDirection.ANY);
            assertTrue(out.isPresent(), () -> "decode returned empty for " + pkt.getClass().getSimpleName());
            assertEquals(secret, out.get().getSessionId());
            assertEquals(pkt.getClass(), out.get().getPacketUntyped(enc).getClass());
        }
    }

    @Test
    void truncatedOrMismagickedBuffersDecodeToEmpty() throws Exception {
        UUID secret = UUID.randomUUID();
        AesEncryption enc = new AesEncryption(new byte[32]);

        byte[] full = PacketUdpCodec
            .encode(new PingPacket("127.0.0.1", 24454), secret, enc);

        for (int prefixLength : new int[] { 0, 4, 6, 12 }) {
            byte[] prefix = Arrays.copyOf(full, prefixLength);
            Optional<PacketUdp> out = PacketUdpCodec.decode(ByteStreams.newDataInput(prefix), PacketDirection.ANY);
            assertFalse(out.isPresent(), () -> "expected empty for prefix length " + prefixLength);
        }

        // Wrong magic: valid-length buffer whose leading int is not the magic number.
        byte[] wrongMagic = Arrays.copyOf(full, full.length);
        int badMagic = MAGIC_NUMBER ^ 0xFFFFFFFF;
        wrongMagic[0] = (byte) (badMagic >>> 24);
        wrongMagic[1] = (byte) (badMagic >>> 16);
        wrongMagic[2] = (byte) (badMagic >>> 8);
        wrongMagic[3] = (byte) badMagic;

        Optional<PacketUdp> out = PacketUdpCodec.decode(ByteStreams.newDataInput(wrongMagic), PacketDirection.ANY);
        assertFalse(out.isPresent(), "expected empty for wrong magic number");
    }
}

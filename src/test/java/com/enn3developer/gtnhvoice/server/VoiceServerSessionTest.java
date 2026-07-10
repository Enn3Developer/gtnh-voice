package com.enn3developer.gtnhvoice.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;

/**
 * Anti-replay contract for source-address relearning: AES-GCM authenticates a datagram but does not
 * stop a replay of a genuine one, so the session must only adopt a new source address from a packet
 * strictly newer than the last one accepted - otherwise a replayed datagram from a remote peer's
 * address would redirect the session's inbound audio to them.
 */
class VoiceServerSessionTest {

    private static VoiceServerSession session() {
        return new VoiceServerSession(UUID.randomUUID(), "player", UUID.randomUUID(), new AesEncryption(new byte[32]));
    }

    @Test
    void relearnsAddressOnlyFromAStrictlyNewerPacket() {
        VoiceServerSession session = session();
        InetSocketAddress real = new InetSocketAddress("127.0.0.1", 100);
        InetSocketAddress remotePeer = new InetSocketAddress("10.0.0.1", 200);

        session.touch(real, 1000L);
        assertEquals(real, session.getLastAddress(), "first packet establishes the address");

        // Replay of an older (or equal) captured datagram from the remote peer must NOT move the address.
        session.touch(remotePeer, 500L);
        assertEquals(real, session.getLastAddress(), "older-timestamp replay must not hijack the address");
        session.touch(remotePeer, 1000L);
        assertEquals(real, session.getLastAddress(), "equal-timestamp replay must not hijack the address");

        // A genuinely newer packet (e.g. a real NAT rebind) still relearns the address.
        InetSocketAddress rebind = new InetSocketAddress("127.0.0.1", 300);
        session.touch(rebind, 1500L);
        assertEquals(rebind, session.getLastAddress(), "a newer packet relearns a real address change");
    }
}

package com.enn3developer.gtnhvoice.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.api.util.AudioUtil;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.JavaOpusDecoder;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.JavaOpusEncoder;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.ServerPacketUdpHandler;

/**
 * Loopback proof that the MC-independent UDP transport actually moves bytes: encodes real
 * Opus audio into a {@link PlayerAudioPacket}, sends it over a real datagram channel from
 * {@link UdpTransportClient} to {@link UdpTransportServer} on 127.0.0.1, and asserts the
 * payload survives the round trip intact.
 */
class UdpTransportLoopbackTest {

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono
    private static final int MTU_SIZE = 1275; // max Opus frame size per RFC 6716

    @Test
    void sentPacketArrivesIntactOverRealUdpLoopback() throws Exception {
        short[] sineSamples = generateSine(FRAME_SIZE, SAMPLE_RATE, 440.0);

        byte[] opusData;
        try (JavaOpusEncoder encoder = new JavaOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU_SIZE)) {
            encoder.open();
            opusData = encoder.encode(sineSamples);
        }

        UUID secret = UUID.randomUUID();
        AesEncryption encryption = new AesEncryption(new byte[32]);
        UUID activationId = UUID.randomUUID();
        long sequenceNumber = 42L;
        short distance = 16;
        PlayerAudioPacket sentPacket = new PlayerAudioPacket(sequenceNumber, opusData, activationId, distance, false);

        BlockingQueue<PacketUdp> received = new ArrayBlockingQueue<>(1);
        UdpTransportServer server = new UdpTransportServer((packet, sender) -> received.add(packet));
        UdpTransportClient client = new UdpTransportClient((packet, sender) -> {});

        try {
            InetSocketAddress serverAddress = server.bind("127.0.0.1", 0);
            client.connect(serverAddress.getHostString(), serverAddress.getPort());

            client.send(sentPacket, secret, encryption);

            PacketUdp receivedPacket = received.poll(5, TimeUnit.SECONDS);
            if (receivedPacket == null) fail("Server did not receive the packet within the timeout");

            assertEquals(secret, receivedPacket.getSessionId());

            PlayerAudioPacket decodedPacket = (PlayerAudioPacket) receivedPacket
                .<ServerPacketUdpHandler>getPacket(encryption);
            assertEquals(sequenceNumber, decodedPacket.getSequenceNumber());
            assertEquals(activationId, decodedPacket.getActivationId());
            assertEquals(distance, decodedPacket.getDistance());
            assertEquals(false, decodedPacket.isStereo());
            assertArrayEquals(opusData, decodedPacket.getData(), "Opus payload must survive the UDP round trip");

            try (JavaOpusDecoder decoder = new JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
                decoder.open();

                short[] decoded = decoder.decode(decodedPacket.getData());
                double level = AudioUtil.calculateAudioLevel(decoded, 0, decoded.length);
                assertTrue(level > -60.0, "Decoded audio should not be silence, but audio level was " + level + " dB");
            }
        } finally {
            client.close();
            server.close();
        }
    }

    private static short[] generateSine(int samples, int sampleRate, double frequencyHz) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            double angle = 2.0 * Math.PI * frequencyHz * i / sampleRate;
            pcm[i] = (short) (Math.sin(angle) * Short.MAX_VALUE * 0.8);
        }
        return pcm;
    }
}

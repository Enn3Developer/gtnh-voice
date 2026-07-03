package com.enn3developer.gtnhvoice.client.slice;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.JavaOpusDecoder;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.JavaOpusEncoder;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.ServerPacketUdpHandler;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportClient;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportServer;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

/**
 * DEV/VALIDATION HARNESS - NOT a real feature. Wires the full local vertical slice end-to-end so it can be proven
 * audible: mic capture -&gt; Opus encode -&gt; real UDP send to 127.0.0.1 -&gt; real UDP receive -&gt; Opus decode
 * -&gt; tiny
 * jitter buffer -&gt; OpenAL playback.
 * <p>
 * The "server" side here is just this same client process receiving its own packets back over a real loopback UDP
 * socket - it decodes and plays audio directly rather than bouncing a clientbound packet back out, since the point
 * of this slice is proving the codec+transport+playback path works, not simulating multiplayer routing.
 */
public class VoiceLoopbackSlice {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono
    private static final int MTU_SIZE = 1275; // max Opus frame size per RFC 6716
    private static final long LOG_INTERVAL_MILLIS = 500L;

    // This harness proves the codec+transport+playback path with a single fixed "speaker" at the origin - it
    // predates per-source routing, so it just picks one arbitrary sourceId for its one AL source.
    private final UUID sliceSourceId = UUID.randomUUID();

    private final UUID secret = UUID.randomUUID();
    private final UUID activationId = UUID.randomUUID();
    private final AesEncryption encryption = new AesEncryption(VoiceProtocol.deriveKey(secret));

    private CaptureManager captureManager;
    private JavaOpusEncoder encoder;
    private JavaOpusDecoder decoder;
    private PlaybackManager playbackManager;
    private SimpleJitterBuffer jitterBuffer;
    private UdpTransportServer server;
    private UdpTransportClient client;
    private EncodeSendWorker encodeSendWorker;

    private final AtomicLong framesReceived = new AtomicLong();
    private final AtomicLong framesDecoded = new AtomicLong();
    private volatile long lastReceiveLogTime = System.currentTimeMillis();

    private volatile boolean running = false;

    public boolean isRunning() {
        return running;
    }

    public void toggle() {
        if (running) {
            stop();
        } else {
            start();
        }
    }

    private void start() {
        try {
            captureManager = new CaptureManager();

            encoder = new JavaOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU_SIZE);
            encoder.open();

            decoder = new JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE);
            decoder.open();

            playbackManager = new PlaybackManager();
            playbackManager.start();
            playbackManager.createSource(sliceSourceId);
            playbackManager.updateSourcePosition(sliceSourceId, 0, 0, 0);
            playbackManager.updateListener(0, 0, 0, 0f, 0f, -1f);

            jitterBuffer = new SimpleJitterBuffer(frame -> playbackManager.submit(sliceSourceId, frame));
            jitterBuffer.start();

            server = new UdpTransportServer(this::onServerPacket);
            InetSocketAddress serverAddress = server.bind("127.0.0.1", 0);

            client = new UdpTransportClient((packet, sender) -> {});
            client.connect("127.0.0.1", serverAddress.getPort());

            captureManager.toggle(); // fresh instance: always starts capture

            encodeSendWorker = new EncodeSendWorker(
                captureManager.getFrameQueue(),
                encoder,
                client,
                secret,
                encryption,
                activationId);
            encodeSendWorker.start();

            framesReceived.set(0);
            framesDecoded.set(0);
            lastReceiveLogTime = System.currentTimeMillis();

            running = true;
            GtnhVoice.LOG.info("[Slice] Toggled ON, loopback server bound on {}", serverAddress);
        } catch (Exception e) {
            GtnhVoice.LOG.error("[Slice] Failed to start loopback slice", e);
            stop();
        }
    }

    private void stop() {
        running = false;

        if (encodeSendWorker != null) {
            encodeSendWorker.shutdown();
            try {
                encodeSendWorker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            encodeSendWorker = null;
        }

        if (captureManager != null && captureManager.isCapturing()) {
            captureManager.toggle();
        }
        captureManager = null;

        if (client != null) {
            client.close();
            client = null;
        }

        if (server != null) {
            server.close();
            server = null;
        }

        if (jitterBuffer != null) {
            jitterBuffer.shutdown();
            jitterBuffer = null;
        }

        if (playbackManager != null) {
            playbackManager.stop();
            playbackManager = null;
        }

        if (encoder != null) {
            encoder.close();
            encoder = null;
        }

        if (decoder != null) {
            decoder.close();
            decoder = null;
        }

        GtnhVoice.LOG.info("[Slice] Toggled OFF");
    }

    private void onServerPacket(com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp packetUdp,
        InetSocketAddress sender) {
        try {
            Object packet = packetUdp.<ServerPacketUdpHandler>getPacket(encryption);
            if (!(packet instanceof PlayerAudioPacket)) return;

            PlayerAudioPacket audioPacket = (PlayerAudioPacket) packet;
            framesReceived.incrementAndGet();

            short[] decoded = decoder.decode(audioPacket.getData());
            framesDecoded.incrementAndGet();

            jitterBuffer.push(decoded);
        } catch (IOException | CodecException e) {
            GtnhVoice.LOG.error("[Slice] Failed to decode received frame", e);
        }

        long now = System.currentTimeMillis();
        if (now - lastReceiveLogTime >= LOG_INTERVAL_MILLIS) {
            GtnhVoice.LOG.info("[Slice] framesReceived={} framesDecoded={}", framesReceived.get(), framesDecoded.get());
            lastReceiveLogTime = now;
        }
    }
}

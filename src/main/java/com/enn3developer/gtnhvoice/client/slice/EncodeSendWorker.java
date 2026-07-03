package com.enn3developer.gtnhvoice.client.slice;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioEncoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportClient;

/**
 * Dev-slice worker thread: drains captured mic frames off a queue, Opus-encodes them, and sends each as a
 * {@link PlayerAudioPacket} over the real UDP transport. Reuses the existing capture hand-off queue rather than
 * duplicating capture.
 */
final class EncodeSendWorker extends Thread {

    private static final short DISTANCE = 0;
    private static final long LOG_INTERVAL_MILLIS = 500L;

    private final BlockingQueue<short[]> captureFrameQueue;
    private final AudioEncoder encoder;
    private final UdpTransportClient client;
    private final UUID secret;
    private final UUID activationId;

    private volatile boolean running = true;

    EncodeSendWorker(BlockingQueue<short[]> captureFrameQueue, AudioEncoder encoder, UdpTransportClient client,
        UUID secret, UUID activationId) {
        super("gtnhvoice-slice-encode-send");
        this.captureFrameQueue = captureFrameQueue;
        this.encoder = encoder;
        this.client = client;
        this.secret = secret;
        this.activationId = activationId;
        setDaemon(true);
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        AtomicLong sequenceNumber = new AtomicLong();
        long framesEncoded = 0;
        long framesSent = 0;
        long lastLogTime = System.currentTimeMillis();

        while (running) {
            short[] frame;
            try {
                frame = captureFrameQueue.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }

            if (frame != null) {
                try {
                    byte[] encoded = encoder.encode(frame);
                    framesEncoded++;

                    PlayerAudioPacket packet = new PlayerAudioPacket(
                        sequenceNumber.getAndIncrement(),
                        encoded,
                        activationId,
                        DISTANCE,
                        false);
                    client.send(packet, secret);
                    framesSent++;
                } catch (CodecException e) {
                    GtnhVoice.LOG.error("[Slice] Failed to encode captured frame", e);
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                GtnhVoice.LOG.info("[Slice] framesEncoded={} framesSent={}", framesEncoded, framesSent);
                lastLogTime = now;
            }
        }
    }
}

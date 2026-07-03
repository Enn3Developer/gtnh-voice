package com.enn3developer.gtnhvoice.client;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioEncoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportClient;

/**
 * Drains {@link com.enn3developer.gtnhvoice.client.capture.CaptureManager}'s frame queue for the
 * lifetime of a voice session, Opus-encodes each frame, and sends it as a {@link
 * PlayerAudioPacket} over the session's real UDP link. Runs continuously once a session is
 * connected regardless of whether the capture keybind is currently on - if nothing is being
 * captured, the queue is simply empty and this thread idles.
 */
final class CaptureSendWorker extends Thread {

    private static final short DISTANCE = 0;
    private static final long LOG_INTERVAL_MILLIS = 5000L;

    private final BlockingQueue<short[]> captureFrameQueue;
    private final AudioEncoder encoder;
    private final UdpTransportClient client;
    private final UUID secret;
    private final Encryption encryption;
    private final UUID activationId;

    private volatile boolean running = true;

    CaptureSendWorker(BlockingQueue<short[]> captureFrameQueue, AudioEncoder encoder, UdpTransportClient client,
        UUID secret, Encryption encryption, UUID activationId) {
        super("gtnhvoice-capture-send");
        this.captureFrameQueue = captureFrameQueue;
        this.encoder = encoder;
        this.client = client;
        this.secret = secret;
        this.encryption = encryption;
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
        long framesSent = 0;
        long lastLoggedFramesSent = 0;
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

                    PlayerAudioPacket packet = new PlayerAudioPacket(
                        sequenceNumber.getAndIncrement(),
                        encoded,
                        activationId,
                        DISTANCE,
                        false);
                    client.send(packet, secret, encryption);
                    framesSent++;
                } catch (CodecException e) {
                    GtnhVoice.LOG.error("[Voice] Failed to encode captured frame", e);
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                if (framesSent != lastLoggedFramesSent) {
                    GtnhVoice.LOG.info("[Voice] framesSent={}", framesSent);
                    lastLoggedFramesSent = framesSent;
                }
                lastLogTime = now;
            }
        }
    }
}

package com.enn3developer.gtnhvoice.client;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioEncoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.audio.filter.rnnoise.NoiseSuppressionFilter;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportClient;

/**
 * Drains {@link com.enn3developer.gtnhvoice.client.capture.CaptureManager}'s frame queue for the
 * lifetime of a voice session, Opus-encodes each frame, and sends it as a {@link
 * PlayerAudioPacket} over the session's real UDP link. The capture device itself is started
 * alongside this worker for the lifetime of the session (see {@link VoiceClientManager}) and runs
 * continuously regardless of the activation gate below.
 * <p>
 * Every polled frame is first denoised via {@link NoiseSuppressionFilter} if one is bound (a
 * cleaner signal makes the activation gate's RMS threshold more accurate), then passed through the
 * {@link ActivationGate}: frames are only encoded+sent while the gate is open (VA above threshold,
 * or PTT key held). Frames while the gate is closed are dropped here - the capture device itself
 * keeps running regardless.
 */
final class CaptureSendWorker extends Thread {

    private static final short DISTANCE = 0;
    private static final long LOG_INTERVAL_MILLIS = 5000L;

    private final BlockingQueue<short[]> captureFrameQueue;
    private final AudioEncoder encoder;
    private final NoiseSuppressionFilter noiseSuppressionFilter;
    private final UdpTransportClient client;
    private final UUID secret;
    private final Encryption encryption;
    private final UUID activationId;
    private final ActivationGate activationGate;

    private volatile boolean running = true;

    // Only ever touched from this worker thread's own run loop.
    private long sequenceNumber;
    private long framesSent;
    private boolean wasTransmitting;

    CaptureSendWorker(BlockingQueue<short[]> captureFrameQueue, AudioEncoder encoder,
        NoiseSuppressionFilter noiseSuppressionFilter, UdpTransportClient client, UUID secret, Encryption encryption,
        UUID activationId, ActivationGate activationGate) {
        super("gtnhvoice-capture-send");
        this.captureFrameQueue = captureFrameQueue;
        this.encoder = encoder;
        this.noiseSuppressionFilter = noiseSuppressionFilter;
        this.client = client;
        this.secret = secret;
        this.encryption = encryption;
        this.activationId = activationId;
        this.activationGate = activationGate;
        setDaemon(true);
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        long lastLoggedFramesSent = 0;
        long lastLogTime = System.currentTimeMillis();

        while (running) {
            short[] frame;
            try {
                frame = captureFrameQueue.take();
            } catch (InterruptedException e) {
                break;
            }

            frame = denoise(frame);

            boolean transmitting = activationGate.shouldTransmit(frame);
            if (transmitting) {
                encodeAndSend(frame);
            }
            wasTransmitting = transmitting;

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

    private short[] denoise(short[] frame) {
        if (noiseSuppressionFilter == null) return frame;

        try {
            return noiseSuppressionFilter.process(frame);
        } catch (CodecException e) {
            GtnhVoice.LOG.error("[Voice] Failed to denoise captured frame, sending it unprocessed", e);
            return frame;
        }
    }

    private void encodeAndSend(short[] frame) {
        try {
            // Closed->open edge: fresh speech segment, don't carry encoder state across the gap.
            if (!wasTransmitting) {
                encoder.reset();
            }

            byte[] encoded = encoder.encode(frame);

            PlayerAudioPacket packet = new PlayerAudioPacket(sequenceNumber++, encoded, activationId, DISTANCE, false);
            client.send(packet, secret, encryption);
            framesSent++;
        } catch (CodecException e) {
            GtnhVoice.LOG.error("[Voice] Failed to encode captured frame", e);
        }
    }
}

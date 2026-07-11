package com.enn3developer.gtnhvoice.client;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.enn3developer.gtnhvoice.Config;
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
 * Every polled frame first gets the user's mic gain ({@link Config#micGain}, read live so the
 * settings slider takes effect on the next frame), is then denoised via
 * {@link NoiseSuppressionFilter} if one is bound and {@link Config#denoiseEnabled} is on - both the
 * gain and the denoise toggle are read live, per frame, so the settings GUI applies mid-session (a
 * cleaner signal makes the activation gate's RMS threshold more accurate), then run through the
 * session-independent {@link CapturePcmFilterChain}
 * (addon DSP; owned by {@link VoiceClientManager}, so registrations survive this worker's
 * per-session lifetime), then passed through the {@link ActivationGate}: frames are only
 * encoded+sent while the gate is open (VA above threshold, or PTT key held). Frames while the gate
 * is closed are dropped here - the capture device itself keeps running regardless. The gain -&gt;
 * denoise -&gt; chain -&gt; gate ordering is deliberate: the whole pipeline sees the amplified mic
 * level, filters receive clean speech rather than raw mic noise, and the gate measures what will
 * actually be transmitted - a filter that quiets or mutes the signal correctly closes the gate
 * instead of transmitting shaped noise.
 * <p>
 * When the mic monitor is active (settings GUI Input tab), fully processed frames are also handed
 * to the monitor sink. In VOICE_ACTIVATION mode the monitor follows the gate (hangover included),
 * so the user hears themselves exactly when they would transmit - that is how the threshold gets
 * tuned by ear. In PUSH_TO_TALK mode the monitor is ungated, so the mic test works without holding
 * the key.
 */
final class CaptureSendWorker extends Thread {

    private static final short DISTANCE = 0;
    private static final long LOG_INTERVAL_MILLIS = 5000L;

    private final BlockingQueue<short[]> captureFrameQueue;
    private final AudioEncoder encoder;
    private final NoiseSuppressionFilter noiseSuppressionFilter;
    private final CapturePcmFilterChain pcmFilterChain;
    private final UdpTransportClient client;
    private final UUID sessionId;
    private final Encryption encryption;
    private final UUID activationId;
    private final ActivationGate activationGate;
    private final BooleanSupplier micMonitorActive;
    private final Consumer<short[]> micMonitorSink;

    private volatile boolean running = true;

    // Only ever touched from this worker thread's own run loop.
    private long sequenceNumber;
    private long framesSent;
    private boolean wasTransmitting;

    CaptureSendWorker(BlockingQueue<short[]> captureFrameQueue, AudioEncoder encoder,
        NoiseSuppressionFilter noiseSuppressionFilter, CapturePcmFilterChain pcmFilterChain, UdpTransportClient client,
        UUID sessionId, Encryption encryption, UUID activationId, ActivationGate activationGate,
        BooleanSupplier micMonitorActive, Consumer<short[]> micMonitorSink) {
        super("gtnhvoice-capture-send");
        this.captureFrameQueue = captureFrameQueue;
        this.encoder = encoder;
        this.noiseSuppressionFilter = noiseSuppressionFilter;
        this.pcmFilterChain = pcmFilterChain;
        this.client = client;
        this.sessionId = sessionId;
        this.encryption = encryption;
        this.activationId = activationId;
        this.activationGate = activationGate;
        this.micMonitorActive = micMonitorActive;
        this.micMonitorSink = micMonitorSink;
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

            frame = applyMicGain(frame);
            frame = denoise(frame);
            frame = pcmFilterChain.apply(frame);

            boolean transmitting = activationGate.shouldTransmit(frame);

            // In VA mode the monitor follows the gate - you hear yourself exactly when you'd transmit, which
            // is how the threshold gets tuned by ear. In PTT mode it stays ungated, so the mic test works
            // without holding the key. Nothing downstream mutates the frame, so the monitor queue and the
            // encoder can safely share the same array.
            if (micMonitorActive.getAsBoolean() && (transmitting || !isVoiceActivation())) {
                micMonitorSink.accept(frame);
            }

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

    private static boolean isVoiceActivation() {
        return Config.getActivationMode() == Config.ActivationMode.VOICE_ACTIVATION;
    }

    /**
     * Scales the frame by {@link Config#micGain} (percent, 100 = untouched), saturating at the 16-bit limits
     * rather than wrapping. Returns the input array untouched at 100% - the common case costs one comparison.
     */
    private static short[] applyMicGain(short[] frame) {
        int gainPercent = Config.micGain;
        if (gainPercent == 100) return frame;

        float multiplier = gainPercent / 100f;
        short[] scaled = new short[frame.length];
        for (int i = 0; i < frame.length; i++) {
            int sample = Math.round(frame[i] * multiplier);
            scaled[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
        }
        return scaled;
    }

    private short[] denoise(short[] frame) {
        // The toggle is read per frame so the GUI's Denoise switch applies live (audible immediately in the
        // mic monitor); the filter instance stays bound either way - skipping process() is the off state.
        if (noiseSuppressionFilter == null || !Config.denoiseEnabled) return frame;

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
            client.send(packet, sessionId, encryption);
            framesSent++;
        } catch (CodecException e) {
            GtnhVoice.LOG.error("[Voice] Failed to encode captured frame", e);
        }
    }
}

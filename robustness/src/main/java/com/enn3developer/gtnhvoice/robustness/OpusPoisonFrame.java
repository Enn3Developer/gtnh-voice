package com.enn3developer.gtnhvoice.robustness;

import java.util.Random;

import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.JavaOpusDecoder;

/**
 * Round-3 security review: proves a SINGLE crafted Opus frame, decoded by a FRESH victim decoder (the state a
 * VoiceSource is in at the start of every speech segment), throws a java.lang.AssertionError out of Concentus -
 * an uncaught throwable that kills the victim's jitterbuffer poller thread. Demonstrates the attack is a
 * reliable, replayable single-shot payload, not a needle-in-a-haystack fuzz artifact.
 */
public final class OpusPoisonFrame {

    public static void main(String[] args) throws Exception {
        System.setProperty("gtnhvoice.disableNatives", "true");

        // Discover a payload that poisons a FRESH decoder (matches segment start), then re-verify it against N
        // brand-new decoders to show it's 100% deterministic and replayable.
        byte[] poison = findFreshPoison();
        if (poison == null) {
            System.out.println("[poison] no fresh-decoder poison found in search budget");
            return;
        }

        System.out.println("[poison] candidate len=" + poison.length + " hex=" + hex(poison));

        int trials = 1000;
        int killed = 0;
        String firstMsg = null;
        for (int i = 0; i < trials; i++) {
            JavaOpusDecoder d = new JavaOpusDecoder(48_000, false, 960);
            d.open();
            try {
                d.decode(poison);
            } catch (CodecException e) {
                // survivable - would be caught by VoiceSource
            } catch (Throwable t) {
                killed++;
                if (firstMsg == null) firstMsg = t.getClass().getName() + ": " + t.getMessage();
            }
            d.close();
        }

        System.out.println("[poison] " + killed + "/" + trials
            + " fresh decoders threw an UNCAUGHT (non-CodecException) throwable");
        System.out.println("[poison] throwable = " + firstMsg);
        System.out.println("[poison] => a single replayable " + poison.length
            + "-byte relayed frame permanently kills the victim's poller thread for that speaker.");
    }

    private static byte[] findFreshPoison() {
        Random rnd = new Random(20260710L);
        for (int i = 0; i < 5_000_000; i++) {
            int len = 1 + rnd.nextInt(2048);
            byte[] data = new byte[len];
            rnd.nextBytes(data);

            JavaOpusDecoder d = new JavaOpusDecoder(48_000, false, 960);
            try {
                d.open();
                d.decode(data);
            } catch (CodecException e) {
                d.close();
                continue;
            } catch (Throwable t) {
                d.close();
                return data; // fatal on a fresh decoder
            }
            d.close();
        }
        return null;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte value : b) sb.append(String.format("%02x", value & 0xFF));
        return sb.toString();
    }
}

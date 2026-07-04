package com.enn3developer.gtnhvoice.client;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.enn3developer.gtnhvoice.GtnhVoice;

/**
 * Client-side, app-lifetime source of truth for per-player volume/mute overrides of OTHER players. Pure data (no
 * MC/AL dependencies) so it can be read from the UDP receive path (mute check), written from the Players GUI, and
 * read fresh by {@code VoiceSource}/{@code PlaybackManager} whenever an AL source is (re)created - including after
 * an output-device/HRTF rebuild, since that path already re-derives gain from here rather than caching a stale
 * value. Persisted via {@link com.enn3developer.gtnhvoice.Config}, which owns the actual config file.
 */
public final class PlayerVoiceSettings {

    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 2.0f;
    public static final float DEFAULT_VOLUME = 1.0f;
    private static final float DEFAULT_EPSILON = 0.001f;

    private static final PlayerVoiceSettings INSTANCE = new PlayerVoiceSettings();

    private final Map<UUID, Float> volumes = new ConcurrentHashMap<>();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Set<UUID> muteDropLogged = ConcurrentHashMap.newKeySet();

    public static PlayerVoiceSettings getInstance() {
        return INSTANCE;
    }

    private PlayerVoiceSettings() {}

    public float getVolume(UUID uuid) {
        return volumes.getOrDefault(uuid, DEFAULT_VOLUME);
    }

    /**
     * Sets {@code uuid}'s volume multiplier, clamped to [{@link #MIN_VOLUME}, {@link #MAX_VOLUME}]. Removes the
     * entry entirely at the default value so {@link #exportOverrides()} prunes it automatically.
     */
    public void setVolume(UUID uuid, float volume) {
        float clamped = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, volume));
        if (Math.abs(clamped - DEFAULT_VOLUME) < DEFAULT_EPSILON) {
            volumes.remove(uuid);
        } else {
            volumes.put(uuid, clamped);
        }
    }

    public boolean isMuted(UUID uuid) {
        return muted.contains(uuid);
    }

    public void setMuted(UUID uuid, boolean value) {
        if (value) {
            muted.add(uuid);
        } else {
            muted.remove(uuid);
            muteDropLogged.remove(uuid);
        }
    }

    /**
     * Whether the UDP receive path should log this drop: {@code true} only the first time called since {@code
     * uuid} was last unmuted (or since JVM start), so a continuously-muted speaker logs once per mute session
     * rather than once per packet.
     */
    public boolean markMuteDropLogged(UUID uuid) {
        return muteDropLogged.add(uuid);
    }

    /**
     * Replaces all overrides from persisted config entries, each formatted {@code uuid,volume,muted} (see
     * {@link com.enn3developer.gtnhvoice.Config}). Malformed entries are skipped and logged, never thrown.
     */
    public void loadOverrides(String[] entries) {
        volumes.clear();
        muted.clear();
        muteDropLogged.clear();

        for (String entry : entries) {
            String[] parts = entry.split(",", -1);
            if (parts.length != 3) {
                GtnhVoice.LOG.warn("[PlayerVoice] Skipping malformed playerOverrides entry: {}", entry);
                continue;
            }

            try {
                UUID uuid = UUID.fromString(parts[0]);
                setVolume(uuid, Float.parseFloat(parts[1]));
                setMuted(uuid, Boolean.parseBoolean(parts[2]));
            } catch (IllegalArgumentException e) {
                GtnhVoice.LOG.warn("[PlayerVoice] Skipping malformed playerOverrides entry: {}", entry);
            }
        }

        GtnhVoice.LOG.info("[PlayerVoice] Loaded {} volume override(s), {} muted", volumes.size(), muted.size());
    }

    /**
     * Serializes every non-default override (custom volume and/or muted) to the {@code uuid,volume,muted} format
     * {@link #loadOverrides} expects. Players at default volume and unmuted are omitted entirely.
     */
    public String[] exportOverrides() {
        Set<UUID> uuids = new HashSet<>(volumes.keySet());
        uuids.addAll(muted);

        String[] result = new String[uuids.size()];
        int i = 0;
        for (UUID uuid : uuids) {
            result[i++] = uuid + "," + getVolume(uuid) + "," + isMuted(uuid);
        }
        return result;
    }
}

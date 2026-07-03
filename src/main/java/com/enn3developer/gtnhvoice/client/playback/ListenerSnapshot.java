package com.enn3developer.gtnhvoice.client.playback;

/**
 * Immutable snapshot of the local player's position/look direction, published by the client tick and read by
 * {@link PlaybackThread} to drive the shared AL listener. Mirrors the position-snapshot discipline used server-side
 * by {@code PlayerSnapshot}: the audio thread never touches live MC world state directly.
 */
final class ListenerSnapshot {

    static final ListenerSnapshot ORIGIN = new ListenerSnapshot(0, 0, 0, 0f, 0f, -1f);

    final double x;
    final double y;
    final double z;
    final float lookX;
    final float lookY;
    final float lookZ;

    ListenerSnapshot(double x, double y, double z, float lookX, float lookY, float lookZ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.lookX = lookX;
        this.lookY = lookY;
        this.lookZ = lookZ;
    }
}

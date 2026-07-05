package com.enn3developer.gtnhvoice.client.playback;

import java.util.Map;
import java.util.UUID;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The single dispatch funnel for every {@link PlaybackLifecycleListener} event - all fire sites in
 * {@link PlaybackThread} and {@link SourceChannelPool} go through these helpers, and any future lifecycle site
 * must too. Listeners are read fresh from {@link PlaybackManager#lifecycleListenersView()} at every fire, so
 * registrations survive start/stop cycles; each listener is dispatched isolated through the shared
 * {@link IsolatedRunner} (the same instance, and thus the same error-log throttle, that queued commands use) - a
 * broken one can neither kill the pump loop nor starve the others. This class is also the future home of
 * per-addon error throttling, once dispatch failures need blaming per listener instead of per thread.
 * <p>
 * A leaf by design: it holds neither the source pool nor the device context - fire methods that need external
 * data take it as arguments. All dispatch runs on the playback thread with the live context bound (unit tests
 * aside); no instance state exists beyond the two final collaborators.
 */
@Lwjgl3Aware
final class LifecycleEventDispatcher {

    private final PlaybackManager manager;
    private final IsolatedRunner isolatedRunner;

    LifecycleEventDispatcher(PlaybackManager manager, IsolatedRunner isolatedRunner) {
        this.manager = manager;
        this.isolatedRunner = isolatedRunner;
    }

    /**
     * The single funnel announcing that a context has become the live output - called from
     * {@link PlaybackThread}'s startup path and {@code performRebuild}'s success path (which covers the
     * default-device fallback too), always with the new context bound and current and before any AL sources exist
     * on it. {@code deviceHandle} is the ALC device the context lives on, so listeners can run ALC extension
     * checks.
     */
    void fireContextCreated(long deviceHandle) {
        for (PlaybackLifecycleListener listener : manager.lifecycleListenersView()) {
            isolatedRunner.run(() -> listener.contextCreated(deviceHandle), "Lifecycle listener contextCreated");
        }
    }

    /**
     * The single funnel announcing that the live context is about to die - called before ANY AL teardown at the
     * top of {@code performRebuild} and in {@link PlaybackThread#run}'s finally, both guarded on
     * {@link OutputDeviceContext#hasLiveContext} so a never-announced (or already-announced) context can never
     * fire destroying. The context is still bound and every AL source still exists when listeners run.
     */
    void fireContextDestroying() {
        for (PlaybackLifecycleListener listener : manager.lifecycleListenersView()) {
            isolatedRunner.run(listener::contextDestroying, "Lifecycle listener contextDestroying");
        }
    }

    /**
     * The single funnel announcing that an AL source now exists - called from
     * {@link SourceChannelPool#createSourceChannel}'s one success point, after the source is fully configured and
     * registered in the pool; the alGenSources/alGenBuffers failure returns never announce, since no channel came
     * into existence.
     */
    void fireSourceCreated(UUID sourceId, int sourceHandle) {
        for (PlaybackLifecycleListener listener : manager.lifecycleListenersView()) {
            isolatedRunner
                .run(() -> listener.sourceCreated(sourceId, sourceHandle), "Lifecycle listener sourceCreated");
        }
    }

    /**
     * The single funnel announcing that an AL source is about to be deleted - called from
     * {@link SourceChannelPool#destroySourceChannel} for an individual death and from {@link #fireContextTeardown}
     * for every live source ahead of a whole-context teardown, always before any of that source's AL state is
     * touched. The two paths can't double-fire for one source: the individual path removes the channel from the
     * pool before firing, so a mass teardown's iteration never sees it.
     */
    void fireSourceDestroying(UUID sourceId, int sourceHandle) {
        for (PlaybackLifecycleListener listener : manager.lifecycleListenersView()) {
            isolatedRunner
                .run(() -> listener.sourceDestroying(sourceId, sourceHandle), "Lifecycle listener sourceDestroying");
        }
    }

    /**
     * Announces a whole-context teardown in destruction order, one step ahead of it: {@link #fireSourceDestroying}
     * for every channel in {@code channels} (the pool's live view), then {@link #fireContextDestroying} - all
     * before ANY actual AL teardown begins. The single helper both teardown paths ({@code performRebuild}'s top
     * and {@link PlaybackThread#run}'s finally) call, so their announcement order can't drift apart; callers keep
     * the live-context guard and must still run {@link SourceChannelPool#teardownAlSources}/
     * {@link OutputDeviceContext#teardownContext} afterwards - this only announces, it destroys nothing.
     */
    void fireContextTeardown(Map<UUID, SourceChannelPool.SourceChannel> channels) {
        for (Map.Entry<UUID, SourceChannelPool.SourceChannel> entry : channels.entrySet()) {
            fireSourceDestroying(entry.getKey(), entry.getValue().alSource);
        }
        fireContextDestroying();
    }

    /**
     * The single funnel for the periodic {@link PlaybackLifecycleListener#audioTick} heartbeat - called once per
     * pump iteration from {@link PlaybackThread#run}, after the listener snapshot is applied (so listeners
     * computing spatial state see the freshest AL listener position) and before the per-source pump loop. The
     * CALLER owns the fires-only-with-sources condition: run() only invokes this when
     * {@link SourceChannelPool#isEmpty} is false, which is what keeps idle iterations silent and free.
     */
    void fireAudioTick() {
        for (PlaybackLifecycleListener listener : manager.lifecycleListenersView()) {
            isolatedRunner.run(listener::audioTick, "Lifecycle listener audioTick");
        }
    }
}

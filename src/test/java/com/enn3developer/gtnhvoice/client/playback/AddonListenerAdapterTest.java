package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;

/**
 * Exercises {@link AddonListenerAdapter}'s per-addon isolation without an AL device: every public-listener
 * callback that throws (Errors included - the exact {@code NoClassDefFoundError} a missing {@code @Lwjgl3Aware}
 * produces) is contained by the adapter, arguments pass through unchanged to a well-behaved delegate, and two
 * broken adapters with different addon names keep operating independently (log content itself is not
 * assertable - behavior is). The actual dispatch through {@code LifecycleEventDispatcher} and the attach
 * command path need a live playback thread and are proven in-game.
 */
class AddonListenerAdapterTest {

    private static final UUID SOURCE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    /** Delegate recording every callback as one string, so pass-through argument correctness is assertable. */
    private static final class RecordingDelegate implements IAudioLifecycleListener {

        final List<String> events = new ArrayList<>();

        @Override
        public void contextCreated(long deviceHandle) {
            events.add("contextCreated:" + deviceHandle);
        }

        @Override
        public void contextDestroying() {
            events.add("contextDestroying");
        }

        @Override
        public void sourceCreated(UUID sourceId, int sourceHandle) {
            events.add("sourceCreated:" + sourceId + ":" + sourceHandle);
        }

        @Override
        public void sourceDestroying(UUID sourceId, int sourceHandle) {
            events.add("sourceDestroying:" + sourceId + ":" + sourceHandle);
        }

        @Override
        public void audioTick() {
            events.add("audioTick");
        }
    }

    /** Delegate that throws from every callback, counting invocations to prove it keeps being dispatched. */
    private static final class ThrowingDelegate implements IAudioLifecycleListener {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void contextCreated(long deviceHandle) {
            calls.incrementAndGet();
            throw new NoClassDefFoundError("org.lwjgl.openal.AL10");
        }

        @Override
        public void contextDestroying() {
            calls.incrementAndGet();
            throw new RuntimeException("addon bug");
        }

        @Override
        public void sourceCreated(UUID sourceId, int sourceHandle) {
            calls.incrementAndGet();
            throw new RuntimeException("addon bug");
        }

        @Override
        public void sourceDestroying(UUID sourceId, int sourceHandle) {
            calls.incrementAndGet();
            throw new RuntimeException("addon bug");
        }

        @Override
        public void audioTick() {
            calls.incrementAndGet();
            throw new RuntimeException("addon bug");
        }
    }

    @Test
    void callbacksAndArgumentsPassThrough() {
        RecordingDelegate delegate = new RecordingDelegate();
        AddonListenerAdapter adapter = new AddonListenerAdapter("well-behaved", delegate);

        adapter.contextCreated(7L);
        adapter.sourceCreated(SOURCE_A, 42);
        adapter.audioTick();
        adapter.sourceDestroying(SOURCE_A, 42);
        adapter.contextDestroying();

        assertEquals(
            Arrays.asList(
                "contextCreated:7",
                "sourceCreated:" + SOURCE_A + ":42",
                "audioTick",
                "sourceDestroying:" + SOURCE_A + ":42",
                "contextDestroying"),
            delegate.events);
    }

    @Test
    void everyThrowingCallbackIsContained() {
        ThrowingDelegate delegate = new ThrowingDelegate();
        AddonListenerAdapter adapter = new AddonListenerAdapter("broken", delegate);

        assertDoesNotThrow(() -> adapter.contextCreated(7L));
        assertDoesNotThrow(() -> adapter.sourceCreated(SOURCE_A, 42));
        assertDoesNotThrow(adapter::audioTick);
        assertDoesNotThrow(() -> adapter.sourceDestroying(SOURCE_A, 42));
        assertDoesNotThrow(adapter::contextDestroying);

        assertEquals(5, delegate.calls.get(), "the delegate must still be dispatched every time, not dropped");
    }

    @Test
    void twoBrokenAdaptersDoNotInterfere() {
        // Each adapter carries its OWN throttle: two broken addons each keep getting dispatched and contained,
        // repeatedly, with no shared state between them (log slots are per adapter; behavior is what's
        // assertable here).
        ThrowingDelegate first = new ThrowingDelegate();
        ThrowingDelegate second = new ThrowingDelegate();
        AddonListenerAdapter adapterA = new AddonListenerAdapter("addon-a", first);
        AddonListenerAdapter adapterB = new AddonListenerAdapter("addon-b", second);

        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(adapterA::audioTick);
            assertDoesNotThrow(adapterB::audioTick);
        }

        assertEquals(10, first.calls.get());
        assertEquals(10, second.calls.get());
    }

    @Test
    void attachWithoutLiveThreadRegistersNothingAndDetachIsSafe() {
        // No playback thread -> the attach command is rejected: nothing to replay, nothing registered; the
        // session bridge covers the next session. The returned handle must still detach without incident.
        PlaybackManager manager = new PlaybackManager();

        Object handle = manager.attachAddonListener("addon", new IAudioLifecycleListener() {});

        assertNotNull(handle);
        assertTrue(
            manager.lifecycleListenersView()
                .isEmpty(),
            "no live thread means no registration - the command path is the only registration path");
        assertDoesNotThrow(() -> manager.detachAddonListener(handle));
    }
}

package com.enn3developer.gtnhvoice.client.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.api.client.ICaptureRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IPcmChain;
import com.enn3developer.gtnhvoice.api.client.IRegistration;

/**
 * The one {@code ICaptureRegistrationBuilder} implementation - the capture twin of
 * {@link AudioRegistrationBuilder}, minus the listener assembly (capture bundles are filters only). Raw
 * {@link #filter} registrations and compiled {@link #chain} recipes accumulate into one ordered list, so
 * {@link #done()} preserves exact registration order across both. The bundle records the filters as-registered
 * plus the {@link FilterGate}; the {@link AddonSessionBridge} wraps each in the gate when it wires them. Not
 * thread-safe - a builder is a short-lived, single-caller object; only the activated bundle is shared.
 */
final class CaptureRegistrationBuilder implements ICaptureRegistrationBuilder {

    private final ClientApiBackend backend;
    private final String addonName;
    private final List<ICapturePcmFilter> filters = new ArrayList<>();
    private final List<ChainCaptureFilter> chainFilters = new ArrayList<>();
    private boolean initiallyEnabled = true;
    private boolean consumed;

    CaptureRegistrationBuilder(ClientApiBackend backend, String addonName) {
        this.backend = backend;
        this.addonName = addonName;
    }

    @Override
    public ICaptureRegistrationBuilder filter(@NotNull ICapturePcmFilter filter) {
        requireNotConsumed();
        Objects.requireNonNull(filter, "filter");
        filters.add(filter);
        return this;
    }

    @Override
    public ICaptureRegistrationBuilder chain(@NotNull Consumer<IPcmChain> spec) {
        requireNotConsumed();
        Objects.requireNonNull(spec, "spec");
        PcmChainRecorder recorder = new PcmChainRecorder();
        spec.accept(recorder);
        // compile() throws IllegalArgumentException on an empty spec - fail fast at the call site.
        ChainCaptureFilter chainFilter = new ChainCaptureFilter(recorder.compile());
        filters.add(chainFilter);
        // Also recorded typed, so the backend can reset it per capture session directly, without relaying
        // onNewCaptureSession through every capture-filter decorator.
        chainFilters.add(chainFilter);
        return this;
    }

    @Override
    public ICaptureRegistrationBuilder initiallyEnabled(boolean enabled) {
        requireNotConsumed();
        // Per-bundle scalar, last call wins - not an accumulating filter.
        initiallyEnabled = enabled;
        return this;
    }

    @Override
    public IRegistration done() {
        requireNotConsumed();
        if (filters.isEmpty())
            throw new IllegalStateException("empty registration for '" + addonName + "': add a filter before done()");
        consumed = true;

        FilterGate gate = new FilterGate(initiallyEnabled);
        CaptureRegistrationBundle bundle = new CaptureRegistrationBundle(
            addonName,
            Collections.unmodifiableList(new ArrayList<>(filters)),
            Collections.unmodifiableList(new ArrayList<>(chainFilters)),
            gate);
        backend.addCaptureBundle(bundle);
        return new Registration(gate, () -> backend.removeCaptureBundle(bundle));
    }

    private void requireNotConsumed() {
        if (consumed) throw new IllegalStateException(
            "registration builder for '" + addonName + "' is single-use and was already done() - open a new one");
    }
}

package com.enn3developer.gtnhvoice.client.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.api.client.ICaptureRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IRegistration;

/**
 * The one {@code ICaptureRegistrationBuilder} implementation - the capture twin of
 * {@link AudioRegistrationBuilder}, minus the listener assembly (capture bundles are filters only). Not
 * thread-safe - a builder is a short-lived, single-caller object; only the activated bundle is shared.
 */
final class CaptureRegistrationBuilder implements ICaptureRegistrationBuilder {

    private final ClientApiBackend backend;
    private final String addonName;
    private final List<ICapturePcmFilter> filters = new ArrayList<>();
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
    public IRegistration done() {
        requireNotConsumed();
        if (filters.isEmpty()) throw new IllegalStateException(
            "empty registration for '" + addonName + "': add a filter before done()");
        consumed = true;

        CaptureRegistrationBundle bundle = new CaptureRegistrationBundle(
            addonName,
            Collections.unmodifiableList(new ArrayList<>(filters)));
        backend.addCaptureBundle(bundle);
        return new Registration(() -> backend.removeCaptureBundle(bundle));
    }

    private void requireNotConsumed() {
        if (consumed) throw new IllegalStateException(
            "registration builder for '" + addonName + "' is single-use and was already done() - open a new one");
    }
}

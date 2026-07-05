package com.enn3developer.gtnhvoice.api.client;

import org.jetbrains.annotations.NotNull;

/**
 * Fluent, single-use assembler for one capture-side registration bundle, created only by
 * {@link IClientCaptureApi#register}. Chain filters - every method returns this builder, every method may be
 * called repeatedly and ALL calls accumulate (two filters both run, in registration order) - then terminate
 * with {@link #done()}, which activates the bundle and returns the {@link IRegistration} handle that removes it
 * as a whole.
 * <p>
 * Nothing is registered until {@link #done()}: an abandoned builder leaks nothing. An empty bundle is a caller
 * bug - {@code done()} without at least one filter throws {@link IllegalStateException}. Single use is
 * enforced: any call after {@code done()}, including a second {@code done()}, throws
 * {@link IllegalStateException}. See {@link IClientCaptureApi#register} for the durability contract.
 */
public interface ICaptureRegistrationBuilder {

    /**
     * Adds a PCM filter on outgoing mic audio to the bundle - see {@link ICapturePcmFilter} for the chain
     * position, threading, format and failure contract. Filters across all bundles run in registration order.
     */
    ICaptureRegistrationBuilder filter(@NotNull ICapturePcmFilter filter);

    /**
     * Terminal: activates the accumulated bundle durably and returns the handle that removes it. The builder is
     * dead afterwards - hold on to the handle, not the builder.
     *
     * @return the bundle's one {@link IRegistration} handle
     * @throws IllegalStateException if the bundle is empty, or if {@code done()} already ran
     */
    IRegistration done();
}

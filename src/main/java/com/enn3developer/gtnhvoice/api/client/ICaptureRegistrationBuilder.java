package com.enn3developer.gtnhvoice.api.client;

import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;

/**
 * Fluent, single-use assembler for one capture-side registration bundle, created only by
 * {@link IVoiceAddon#capture()}. Chain filters - every method returns this builder, every method may be
 * called repeatedly and ALL calls accumulate (two filters both run, in registration order) - then terminate
 * with {@link #done()}, which activates the bundle and returns the {@link IRegistration} handle that removes it
 * as a whole.
 * <p>
 * Nothing is registered until {@link #done()}: an abandoned builder leaks nothing. An empty bundle is a caller
 * bug - {@code done()} without at least one filter throws {@link IllegalStateException}. Single use is
 * enforced: any call after {@code done()}, including a second {@code done()}, throws
 * {@link IllegalStateException}. See {@link IVoiceAddon#capture()} for the durability contract.
 */
public interface ICaptureRegistrationBuilder {

    /**
     * Adds a PCM filter on outgoing mic audio to the bundle - see {@link ICapturePcmFilter} for the chain
     * position, threading, format and failure contract. Filters across all bundles run in registration order.
     */
    ICaptureRegistrationBuilder filter(@NotNull ICapturePcmFilter filter);

    /**
     * Adds a {@link IPcmChain} recipe as one capture filter - the fluent alternative to hand-writing an
     * {@link ICapturePcmFilter}. The {@code spec} lambda is invoked IMMEDIATELY to record the chain's stages;
     * the compiled filter then sits at the exact seam {@link #filter} occupies (same chain position, threading
     * and failure isolation). Deliberately NOT an overload of {@code filter(...)}: an implicitly-typed lambda is
     * ambiguous between the two, so this carries its own name.
     * <p>
     * Like {@link #filter}, repeated calls ACCUMULATE - two chains both run, in registration order, interleaved
     * with any raw filters by call order - and a non-empty chain counts as a filter for {@link #done()}'s
     * empty-bundle check.
     *
     * @param spec records the chain's stages onto the supplied {@link IPcmChain}; non-null
     * @throws NullPointerException     if {@code spec} is null
     * @throws IllegalArgumentException if {@code spec} records no stages, or if any stage's arguments are invalid
     *                                  (both surface here, at the call site, not later on the audio path)
     */
    ICaptureRegistrationBuilder chain(@NotNull Consumer<IPcmChain> spec);

    /**
     * Sets whether this bundle's filters start enabled - see {@link IRegistration#setFilterEnabled(boolean)} for
     * what the gate does. Unlike the accumulating filter methods this is a per-bundle SCALAR: repeated calls do
     * NOT stack, the LAST call wins (like {@code auxiliarySends}). Not calling it leaves the bundle enabled.
     *
     * @param enabled the bundle's initial filter-gate state; default {@code true}
     */
    ICaptureRegistrationBuilder initiallyEnabled(boolean enabled);

    /**
     * Terminal: activates the accumulated bundle durably and returns the handle that removes it. The builder is
     * dead afterwards - hold on to the handle, not the builder.
     *
     * @return the bundle's one {@link IRegistration} handle
     * @throws IllegalStateException if the bundle is empty, or if {@code done()} already ran
     */
    IRegistration done();
}

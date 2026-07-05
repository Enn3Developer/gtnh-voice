package com.enn3developer.gtnhvoice.server.group;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;

/**
 * Context-free recipient predicates for {@link RecipientSelection#filter} - identity-based rules that need no
 * position snapshot (for those, see the factories on {@link RoutingContext}). Combine with the standard
 * {@link Predicate} combinators ({@code and}/{@code or}/{@code negate}); no custom combinators are provided.
 * The predicates run on the UDP/Netty thread under the {@link IGroup#route} contract - read-only, non-blocking.
 */
public final class Filters {

    private Filters() {}

    /**
     * Keeps sessions whose player UUID is in {@code members} (party/faction/channel rosters). The predicate
     * reads the live collection at test time - no defensive copy is taken, so the caller owns thread safety of
     * their collection with respect to the UDP/Netty thread.
     */
    public static Predicate<IVoiceSession> memberOf(@NotNull Collection<UUID> members) {
        return session -> members.contains(session.getPlayerUuid());
    }

    /** Keeps exactly the session of {@code playerUuid} (whisper/target routing). */
    public static Predicate<IVoiceSession> player(@NotNull UUID playerUuid) {
        return session -> session.getPlayerUuid()
            .equals(playerUuid);
    }
}

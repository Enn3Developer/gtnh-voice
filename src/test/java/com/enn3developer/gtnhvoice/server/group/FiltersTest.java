package com.enn3developer.gtnhvoice.server.group;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Exercises {@link Filters}' context-free predicates directly - they need no routing context or snapshot, only
 * sessions, so each test builds sessions and probes the predicate.
 */
class FiltersTest {

    @Test
    void memberOfKeepsMembersAndDropsOthers() {
        VoiceServerSession member = session("member");
        VoiceServerSession outsider = session("outsider");
        Set<UUID> roster = new HashSet<>();
        roster.add(member.getPlayerUuid());

        Predicate<VoiceServerSession> filter = Filters.memberOf(roster);

        assertTrue(filter.test(member));
        assertFalse(filter.test(outsider));
    }

    @Test
    void memberOfReflectsLiveCollectionMutation() {
        VoiceServerSession joiner = session("joiner");
        VoiceServerSession leaver = session("leaver");
        Set<UUID> roster = new HashSet<>();
        roster.add(leaver.getPlayerUuid());

        Predicate<VoiceServerSession> filter = Filters.memberOf(roster);
        assertTrue(filter.test(leaver));
        assertFalse(filter.test(joiner));

        roster.remove(leaver.getPlayerUuid());
        roster.add(joiner.getPlayerUuid());

        assertFalse(filter.test(leaver));
        assertTrue(filter.test(joiner));
    }

    @Test
    void playerKeepsExactlyTheTarget() {
        VoiceServerSession target = session("target");
        VoiceServerSession bystander = session("bystander");

        Predicate<VoiceServerSession> filter = Filters.player(target.getPlayerUuid());

        assertTrue(filter.test(target));
        assertFalse(filter.test(bystander));
    }

    private static VoiceServerSession session(String name) {
        UUID secret = UUID.randomUUID();
        return new VoiceServerSession(
            UUID.randomUUID(),
            name,
            secret,
            new AesEncryption(VoiceProtocol.deriveKey(secret)));
    }
}

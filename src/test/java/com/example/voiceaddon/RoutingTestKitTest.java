package com.example.voiceaddon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.server.ActivationIds;
import com.enn3developer.gtnhvoice.api.server.GtnhVoiceApi;
import com.enn3developer.gtnhvoice.api.server.IAudioFrame;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.api.server.SourceState;
import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;
import com.enn3developer.gtnhvoice.api.server.group.RoutingTestKit;
import com.enn3developer.gtnhvoice.api.server.group.RoutingTestKit.Delivery;

/**
 * Routing tests the way an addon writes them, through {@link RoutingTestKit}: this file imports nothing from GTNH
 * Voice outside its api packages, and sits outside the mod's packages, so it reaches nothing package-private either.
 * Everyone stands on the x axis of dimension 0 at y 64.
 */
class RoutingTestKitTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000a11ce");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000ca201");
    private static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-00000000da7e");
    private static final UUID RADIO_KEY = ActivationIds.of("voiceaddon", "radio");
    private static final IAudioFrame VOICE = RoutingTestKit.frame(1, ActivationIds.DEFAULT);
    private static final BiPredicate<UUID, IGroup> NO_MEMBERS = (player, group) -> false;

    private final RoutingTestKit kit = new RoutingTestKit();

    @Test
    void sessionsComeFromAUuidAndANameWithOrWithoutAUdpAddress() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.sessionWithoutUdpAddress(BOB, "Bob");

        assertEquals(ALICE, alice.getPlayerUuid());
        assertEquals("Alice", alice.getPlayerName());
        assertTrue(alice.hasUdpAddress());
        assertEquals(BOB, bob.getPlayerUuid());
        assertEquals("Bob", bob.getPlayerName());
        assertFalse(bob.hasUdpAddress());
    }

    @Test
    void eachDeliveryComesBackAsPlainValues() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.session(BOB, "Bob");
        IGroup loudspeaker = group(
            "loudspeaker",
            0,
            context -> context.sendTo(bob, SourceState.POSITIONAL, 10.5, 70, -4));

        List<Delivery> sent = kit.route(alice, VOICE, Map.of(), NO_MEMBERS, loudspeaker);

        assertEquals(1, sent.size());
        Delivery delivery = sent.get(0);
        assertSame(bob, delivery.getRecipient());
        assertEquals(SourceState.POSITIONAL, delivery.getSourceState());
        assertEquals(10.5, delivery.getX());
        assertEquals(70, delivery.getY());
        assertEquals(-4, delivery.getZ());
        assertEquals(ALICE, delivery.getSourceId());
        assertSame(loudspeaker, delivery.getGroup());
    }

    @Test
    void groupsRouteInPriorityOrderWhateverOrderTheyArePassedIn() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.session(BOB, "Bob");
        IVoiceSession carol = kit.session(CAROL, "Carol");
        List<String> routed = new ArrayList<>();
        IGroup call = group("call", 10, context -> {
            routed.add("call");
            context.sendTo(bob, SourceState.FLAT, 0, 0, 0);
        });
        IGroup radioA = group("radio-a", 5, context -> {
            routed.add("radio-a");
            context.sendTo(bob, SourceState.FLAT, 0, 0, 0);
            context.sendTo(carol, SourceState.FLAT, 0, 0, 0);
        });
        IGroup radioB = group("radio-b", 5, context -> routed.add("radio-b"));

        List<Delivery> sent = kit.route(alice, VOICE, Map.of(), NO_MEMBERS, radioB, radioA, call);

        assertEquals(List.of("call", "radio-a", "radio-b"), routed, "priority descending, ties by name");
        assertEquals(List.of("Bob via call", "Carol via radio-a"), describe(sent), "one delivery per player");
    }

    @Test
    void exclusiveSkipsEveryLowerPriorityGroup() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.session(BOB, "Bob");
        IVoiceSession carol = kit.session(CAROL, "Carol");
        IGroup radio = group("radio", 5, context -> {
            context.exclusive();
            context.sendTo(bob, SourceState.FLAT, 0, 0, 0);
        });
        IGroup squad = group("squad", 0, context -> context.sendTo(carol, SourceState.FLAT, 0, 0, 0));

        List<Delivery> sent = kit.route(alice, VOICE, Map.of(), NO_MEMBERS, squad, radio);

        assertEquals(List.of("Bob via radio"), describe(sent));
    }

    @Test
    void aLowerPriorityGroupSeesWhomAHigherOneServed() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.session(BOB, "Bob");
        IVoiceSession carol = kit.session(CAROL, "Carol");
        List<String> radioContacts = new ArrayList<>();
        IGroup call = group("call", 10, context -> context.sendTo(bob, SourceState.FLAT, 0, 0, 0));
        IGroup radio = group("radio", 5, context -> {
            for (IVoiceSession listener : List.of(bob, carol)) {
                if (context.isServed(listener.getPlayerUuid())) continue;
                context.sendTo(listener, SourceState.FLAT, 0, 0, 0);
                radioContacts.add(listener.getPlayerName());
            }
        });

        kit.route(alice, VOICE, Map.of(), NO_MEMBERS, radio, call);

        assertEquals(List.of("Carol"), radioContacts);
    }

    @Test
    void sendsToASessionWithoutAUdpAddressAreDropped() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.sessionWithoutUdpAddress(BOB, "Bob");
        IGroup radio = group("radio", 0, context -> context.sendTo(bob, SourceState.FLAT, 0, 0, 0));

        assertTrue(
            kit.route(alice, VOICE, Map.of(), NO_MEMBERS, radio)
                .isEmpty());
    }

    @Test
    void groupsSeeThePositionSnapshotAndTheMembershipTest() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        kit.session(BOB, "Bob");
        kit.session(CAROL, "Carol");
        kit.session(DAVE, "Dave");
        // A squad: its members within 32 blocks of the speaker.
        IGroup squad = group(
            "squad",
            0,
            context -> context.getSessionsForGroup()
                .excludeSelf()
                .cutoffDistance(32)
                .send(SourceState.POSITIONAL));
        Set<UUID> members = Set.of(ALICE, BOB, DAVE);

        List<Delivery> sent = kit.route(
            alice,
            VOICE,
            positions(at(ALICE, "Alice", 0), at(BOB, "Bob", 10), at(CAROL, "Carol", 10), at(DAVE, "Dave", 100)),
            (player, group) -> group == squad && members.contains(player),
            squad);

        assertEquals(List.of("Bob via squad"), describe(sent), "Carol is no member, Dave is too far");
        assertEquals(List.of(0.0, 64.0, 0.0), position(sent.get(0)), "it plays from the speaker's position");
    }

    @Test
    void groupsSeeTheFramesActivation() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.session(BOB, "Bob");
        IGroup radio = group(
            "radio",
            0,
            context -> {
                if (RADIO_KEY.equals(
                    context.getAudio()
                        .getActivationId()))
                    context.sendTo(bob, SourceState.FLAT, 0, 0, 0);
            });

        List<Delivery> ordinaryVoice = kit.route(alice, VOICE, Map.of(), NO_MEMBERS, radio);
        List<Delivery> keyed = kit.route(alice, RoutingTestKit.frame(2, RADIO_KEY), Map.of(), NO_MEMBERS, radio);

        assertTrue(ordinaryVoice.isEmpty());
        assertEquals(List.of("Bob via radio"), describe(keyed));
    }

    @Test
    void eachRouteIsAFreshFrame() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IVoiceSession bob = kit.session(BOB, "Bob");
        IGroup radio = group("radio", 0, context -> context.sendTo(bob, SourceState.FLAT, 0, 0, 0));
        IAudioFrame nextFrame = RoutingTestKit.frame(2, ActivationIds.DEFAULT);

        kit.route(alice, VOICE, Map.of(), NO_MEMBERS, radio);
        List<Delivery> next = kit.route(alice, nextFrame, Map.of(), NO_MEMBERS, radio);

        assertEquals(List.of("Bob via radio"), describe(next), "bob was served last frame, not this one");
    }

    @Test
    void theBuiltInLocalGroupRoutesThroughTheKitToo() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        kit.session(BOB, "Bob");
        kit.session(CAROL, "Carol");
        IGroup local = GtnhVoiceApi.groupManager()
            .byName("local");
        int range = GtnhVoiceApi.proximityRange();

        List<Delivery> sent = kit.route(
            alice,
            VOICE,
            positions(at(ALICE, "Alice", 0), at(BOB, "Bob", range), at(CAROL, "Carol", range + 1)),
            NO_MEMBERS,
            local);

        assertEquals(List.of("Bob via local"), describe(sent));
        assertEquals(
            SourceState.POSITIONAL,
            sent.get(0)
                .getSourceState());
    }

    @Test
    void misuseFailsLoudly() {
        IVoiceSession alice = kit.session(ALICE, "Alice");
        IGroup radio = group("radio", 0, context -> {});
        IVoiceSession stranger = new RoutingTestKit().session(BOB, "Bob");

        assertThrows(IllegalArgumentException.class, () -> kit.session(ALICE, "Alice again"));
        assertThrows(IllegalArgumentException.class, () -> kit.route(stranger, VOICE, Map.of(), NO_MEMBERS, radio));
        assertThrows(IllegalArgumentException.class, () -> kit.route(alice, VOICE, Map.of(), NO_MEMBERS));
        assertThrows(IllegalArgumentException.class, () -> kit.route(alice, VOICE, Map.of(), NO_MEMBERS, radio, radio));
    }

    private static PlayerSnapshot at(UUID player, String name, double x) {
        return new PlayerSnapshot(player, name, x, 64, 0, 0);
    }

    /** The tick's position snapshot, as the server keys it: by player UUID. */
    private static Map<UUID, PlayerSnapshot> positions(PlayerSnapshot... players) {
        Map<UUID, PlayerSnapshot> positions = new HashMap<>();
        for (PlayerSnapshot player : players) {
            positions.put(player.getUuid(), player);
        }
        return positions;
    }

    private static List<Double> position(Delivery delivery) {
        return List.of(delivery.getX(), delivery.getY(), delivery.getZ());
    }

    private static List<String> describe(List<Delivery> deliveries) {
        List<String> described = new ArrayList<>();
        for (Delivery delivery : deliveries) {
            described.add(
                delivery.getRecipient()
                    .getPlayerName() + " via "
                    + delivery.getGroup()
                        .getName());
        }
        return described;
    }

    private static IGroup group(String name, int priority, Consumer<RoutingContext> behavior) {
        return new IGroup() {

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public String getDisplayName() {
                return name;
            }

            @Override
            public void route(RoutingContext context) {
                behavior.accept(context);
            }
        };
    }
}

package club.pokermadness.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import static club.pokermadness.engine.TableView.*;
import static club.pokermadness.engine.TableView.ActionType.*;
import static org.junit.jupiter.api.Assertions.*;

class HandTest {
    @Test
    void headsUpButtonPostsSmallBlindAndActsFirstThenLast() {
        Hand hand = hand(0, 100, 100);
        Observation first = decision(hand, 0);
        assertEquals(List.of(5, 10), first.players().stream().map(Player::streetContribution).toList());
        assertEquals(5, first.toCall());
        assertTrue(first.board().isEmpty());
        assertEquals(2, first.holeCards().size());
        move(hand, 0, CALL);
        move(hand, 1, CHECK);
        assertEquals(Street.FLOP, decision(hand, 1).street());
        move(hand, 1, CHECK);
        decision(hand, 0);
    }

    @Test
    void multiplayerTurnsAndRaiseTargetsUseTheCurrentStreet() {
        Hand hand = hand(0, 1000, 1000, 1000, 1000);
        assertEquals(20, legal(decision(hand, 3), RAISE).minAmount());
        move(hand, 3, RAISE, 30);
        move(hand, 0, CALL);
        move(hand, 1, CALL);
        Observation bigBlind = decision(hand, 2);
        assertEquals(20, bigBlind.toCall());
        assertEquals(50, legal(bigBlind, RAISE).minAmount());
        assertThrows(IllegalArgumentException.class, () -> hand.act(new Action(RAISE, 20), false));
        move(hand, 2, CALL);
        Observation flop = decision(hand, 1);
        assertEquals(Street.FLOP, flop.street());
        assertEquals(3, flop.board().size());
        assertEquals(120, flop.pot());
        assertEquals(0, flop.players().get(1).streetContribution());
        assertEquals(30, flop.players().get(1).handContribution());
        assertEquals(970, legal(flop, BET).maxAmount());
        assertEquals(20, flop.history().get(5).amount());
    }

    @Test
    void bigBlindRetainsOptionWhenEveryoneLimped() {
        Hand hand = hand(0, 100, 100, 100, 100);
        move(hand, 3, CALL);
        move(hand, 0, CALL);
        move(hand, 1, CALL);
        Observation blind = decision(hand, 2);
        assertEquals(0, blind.toCall());
        assertNotNull(legal(blind, CHECK));
        assertEquals(20, legal(blind, RAISE).minAmount());
    }

    @Test
    void oneShortAllInDoesNotReopenButTwoCan() {
        Hand one = hand(0, 200, 25, 100, 200);
        move(one, 3, RAISE, 20);
        move(one, 0, CALL);
        assertEquals(25, legal(decision(one, 1), RAISE).minAmount());
        assertEquals(25, legal(decision(one, 1), RAISE).maxAmount());
        move(one, 1, RAISE, 25);
        move(one, 2, CALL);
        Observation originalRaiser = decision(one, 3);
        assertEquals(5, originalRaiser.toCall());
        assertNull(legal(originalRaiser, RAISE));

        Hand two = hand(0, 200, 25, 30, 200);
        move(two, 3, RAISE, 20);
        move(two, 0, CALL);
        move(two, 1, RAISE, 25);
        move(two, 2, RAISE, 30);
        Observation reopened = decision(two, 3);
        assertEquals(10, reopened.toCall());
        assertEquals(40, legal(reopened, RAISE).minAmount());
    }

    @Test
    void unactedPlayerCanCompleteShortOpeningButItDoesNotReopenForChecker() {
        Hand hand = hand(0, 100, 100, 15, 100);
        move(hand, 3, CALL);
        move(hand, 0, CALL);
        move(hand, 1, CALL);
        move(hand, 2, CHECK);
        move(hand, 1, CHECK);
        assertEquals(5, legal(decision(hand, 2), BET).minAmount());
        move(hand, 2, BET, 5);
        assertEquals(10, legal(decision(hand, 3), RAISE).minAmount());
        move(hand, 3, CALL);
        move(hand, 0, CALL);
        Observation checker = decision(hand, 1);
        assertEquals(5, checker.toCall());
        assertNull(legal(checker, RAISE));
    }

    @Test
    void shortCallPaysOnlyRemainingStackAndFinalActorCannotRaiseIntoAllIns() {
        Hand hand = hand(0, 200, 22, 40, 100);
        move(hand, 3, FOLD);
        move(hand, 0, CALL);
        move(hand, 1, CALL);
        move(hand, 2, RAISE, 40);
        move(hand, 0, CALL);
        Observation shortCall = decision(hand, 1);
        assertEquals(30, shortCall.toCall());
        assertEquals(12, legal(shortCall, CALL).amount());
        assertNull(legal(shortCall, RAISE));
        move(hand, 1, CALL);
        assertNull(hand.nextDecision(), "Only a matched active player remains; run the board");
        assertEquals(5, hand.result(1).board().size());
    }

    @Test
    void uncontestedRaiseReturnsExcessWithoutExposingCards() {
        Hand hand = hand(0, 100, 100, 100, 100);
        move(hand, 3, RAISE, 50);
        move(hand, 0, FOLD);
        move(hand, 1, FOLD);
        move(hand, 2, FOLD);
        assertNull(hand.nextDecision());
        HandResult result = hand.result(0);
        assertEquals("all_folded", result.reason());
        assertEquals(2, result.holeCards().size());
        assertTrue(result.board().isEmpty());
        assertTrue(result.players().stream().allMatch(p -> p.shownCards().isEmpty()));
        assertEquals(List.of(0, -5, -10, 15), net(result));
        Event returned = result.history().get(result.history().size() - 1);
        assertEquals("return_uncalled", returned.type());
        assertEquals(40, returned.amount());
        assertEquals(10, returned.streetTotal());
    }

    @Test
    void mainPotAndSidePotCanHaveDifferentWinners() {
        Hand hand = new Hand(1, 0, 5, 10, new int[]{40, 70, 100, 100},
                deck("Ks Qs 8s As Kd Qd 7d Ad 2s 2c 3d 4h 5s 9s 6c Jc"));
        move(hand, 3, FOLD);
        move(hand, 0, RAISE, 40);
        move(hand, 1, RAISE, 70);
        Observation finalCall = decision(hand, 2);
        assertEquals(60, finalCall.toCall());
        assertNull(legal(finalCall, RAISE));
        move(hand, 2, CALL);
        assertNull(hand.nextDecision());
        HandResult result = hand.result(3);
        assertEquals("showdown", result.reason());
        assertEquals(List.of("8s", "7d"), result.holeCards());
        assertTrue(result.players().get(3).shownCards().isEmpty());
        assertEquals(List.of(80, -10, -70, 0), net(result));
        assertEquals(List.of(new Award(0, 120)), result.pots().get(0).awards());
        assertEquals(List.of(new Award(1, 60)), result.pots().get(1).awards());
    }

    @Test
    void oddChipGoesClockwiseFromButtonAndFoldedChipsStayInPot() {
        Hand hand = new Hand(1, 0, 5, 10, new int[]{100, 100, 100, 100},
                deck("2c 3c 4c 5c 6d 7d 8d 9d 2s Th Jh Qh 3s Kh 4s Ah"));
        move(hand, 3, FOLD);
        move(hand, 0, RAISE, 21);
        move(hand, 1, CALL);
        move(hand, 2, CALL);
        move(hand, 1, CHECK);
        move(hand, 2, FOLD);
        checkOrCallUntilEnd(hand);
        HandResult result = hand.result(0);
        assertEquals(List.of(10, 11, -21, 0), net(result));
        assertEquals(List.of(new Award(1, 32), new Award(0, 31)), result.pots().get(0).awards());
    }

    @Test
    void randomLegalHandsConserveChipsAndNeverRepeatCards() {
        var random = new SplittableRandom(731);
        for (int round = 0; round < 500; round++) {
            int count = 2 + random.nextInt(5);
            int[] stacks = IntStream.generate(() -> 10 + random.nextInt(491)).limit(count).toArray();
            var hand = new Hand(1, round % count, 5, 10, stacks, Cards.shuffled(random));
            Observation observation;
            int actions = 0;
            while ((observation = hand.nextDecision()) != null) {
                assertTrue(++actions < 400, "Hand did not reach settlement");
                assertEquals(observation.pot(), observation.pots().stream().mapToInt(Pot::amount).sum());
                assertEquals(observation.pot(), observation.players().stream().mapToInt(Player::handContribution).sum());
                assertEquals("active", observation.players().get(observation.heroSeat()).status());
                List<LegalAction> legal = observation.legalActions();
                int choice = random.nextInt(10);
                LegalAction option = legal.get(choice < 2 ? 0 : choice < 8 ? 1 : legal.size() - 1);
                Integer amount = option.action().isAggressive()
                        ? random.nextInt(option.minAmount(), option.maxAmount() + 1) : null;
                hand.act(new Action(option.action(), amount), false);
            }
            HandResult end = hand.result(0);
            assertEquals(0, end.players().stream().mapToInt(ResultPlayer::netChips).sum());
            var cards = new HashSet<>(end.board());
            for (int seat = 0; seat < count; seat++) {
                for (String card : hand.result(seat).holeCards()) assertTrue(cards.add(card));
            }
            for (SettledPot pot : end.pots()) {
                assertEquals(pot.amount(), pot.awards().stream().mapToInt(Award::amount).sum());
                assertTrue(pot.awards().stream().allMatch(a -> pot.eligibleSeats().contains(a.seat())));
            }
        }
    }

    static Hand hand(int button, int... stacks) {
        return new Hand(1, button, 5, 10, stacks, Cards.shuffled(new SplittableRandom(12)));
    }

    static Observation decision(Hand hand, int seat) {
        Observation decision = hand.nextDecision();
        assertNotNull(decision);
        assertEquals(seat, decision.heroSeat());
        return decision;
    }

    static void move(Hand hand, int seat, ActionType type) {
        move(hand, seat, type, null);
    }

    static void move(Hand hand, int seat, ActionType type, Integer amount) {
        decision(hand, seat);
        hand.act(new Action(type, amount), false);
    }

    static LegalAction legal(Observation observation, ActionType type) {
        return observation.legalActions().stream().filter(a -> a.action() == type).findFirst().orElse(null);
    }

    static void checkOrCallUntilEnd(Hand hand) {
        Observation observation;
        while ((observation = hand.nextDecision()) != null) {
            hand.act(Action.simple(observation.toCall() == 0 ? CHECK : CALL), false);
        }
    }

    private static List<Integer> net(HandResult result) {
        return result.players().stream().map(ResultPlayer::netChips).toList();
    }

    private static int[] deck(String prefix) {
        var ordered = new ArrayList<>(Arrays.stream(prefix.split(" ")).map(Cards::parse).toList());
        for (int i = 0; i < 52; i++) if (!ordered.contains(i)) ordered.add(i);
        return ordered.stream().mapToInt(Integer::intValue).toArray();
    }
}

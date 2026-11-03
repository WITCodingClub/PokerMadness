package club.pokermadness.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static club.pokermadness.engine.TableView.*;

/** One hand. Neither its deck nor its mutable seat state is sent to bot processes. */
final class Hand {
    private final int number;
    private final int button;
    private final int smallBlind;
    private final int bigBlind;
    private final Seat[] seats;
    private final int totalChips;
    private final int[] deck;
    private final int[] board = new int[5];
    private final List<Event> history = new ArrayList<>();
    private Street street = Street.PREFLOP;
    private int nextCard;
    private int boardSize;
    private int currentBet;
    private int lastFullIncrement;
    private int cursor;
    private Observation pendingDecision;
    private List<ResultPlayer> resultPlayers;
    private List<SettledPot> settledPots;
    private String resultReason;

    Hand(int number, int button, int smallBlind, int bigBlind, int[] startingStacks, int[] deck) {
        if (number < 1 || startingStacks.length < 2 || startingStacks.length > 6
                || button < 0 || button >= startingStacks.length
                || smallBlind < 1 || bigBlind < smallBlind) {
            throw new IllegalArgumentException("Invalid hand settings");
        }
        this.number = number;
        this.button = button;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.lastFullIncrement = bigBlind;
        this.deck = validateDeck(deck);
        this.seats = new Seat[startingStacks.length];
        long sum = 0;
        for (int i = 0; i < seats.length; i++) {
            if (startingStacks[i] < bigBlind) throw new IllegalArgumentException("Stacks must cover the big blind");
            seats[i] = new Seat(i, startingStacks[i]);
            sum += startingStacks[i];
        }
        if (sum > Integer.MAX_VALUE) throw new IllegalArgumentException("Table chips exceed the protocol range");
        this.totalChips = (int) sum;

        int smallSeat = seats.length == 2 ? button : nextSeat(button);
        int bigSeat = nextSeat(smallSeat);
        for (int card = 0; card < 2; card++) {
            for (int offset = 0; offset < seats.length; offset++) {
                seats[(nextSeat(button) + offset) % seats.length].hole[card] = this.deck[nextCard++];
            }
        }
        postBlind(smallSeat, "small", smallBlind);
        postBlind(bigSeat, "big", bigBlind);
        currentBet = bigBlind;
        cursor = nextSeat(bigSeat);
        for (Seat seat : seats) seat.pending = seat.active();
    }

    private static int[] validateDeck(int[] cards) {
        if (cards.length != 52) throw new IllegalArgumentException("Expected a standard 52-card deck");
        long seen = 0;
        for (int card : cards) {
            if (card < 0 || card >= 52 || (seen & (1L << card)) != 0) {
                throw new IllegalArgumentException("Invalid or repeated card");
            }
            seen |= 1L << card;
        }
        return cards.clone();
    }

    /** Advances forced steps and returns the next actor's snapshot, or null if the hand ended. */
    Observation nextDecision() {
        if (pendingDecision != null) return pendingDecision;
        while (resultPlayers == null) {
            if (Arrays.stream(seats).filter(seat -> !seat.folded).count() == 1) {
                refundUncalled();
                finish(false);
                break;
            }
            int active = activeCount();
            for (int offset = 0; offset < seats.length && active > 0; offset++) {
                Seat seat = seats[(cursor + offset) % seats.length];
                if (seat.active() && (active == 1 ? seat.streetPaid < currentBet : seat.pending)) {
                    pendingDecision = observe(seat, active);
                    return pendingDecision;
                }
            }
            refundUncalled();
            if (street == Street.RIVER) finish(true);
            else dealStreet();
        }
        return null;
    }

    void act(Action action, boolean fallback) {
        if (pendingDecision == null || !pendingDecision.allows(action)) {
            throw new IllegalArgumentException("Action does not match the pending decision");
        }
        Seat seat = seats[pendingDecision.heroSeat()];
        int paid = 0;
        switch (action.type()) {
            case FOLD -> seat.folded = true;
            case CHECK -> { }
            case CALL -> {
                paid = Math.min(seat.stack, currentBet - seat.streetPaid);
                seat.commit(paid);
            }
            case BET, RAISE -> {
                int target = action.amount();
                paid = target - seat.streetPaid;
                seat.commit(paid);
                if (currentBet < bigBlind && target >= bigBlind) {
                    // A completion or larger first full wager establishes the opening size.
                    lastFullIncrement = target;
                } else if (target - currentBet >= lastFullIncrement) {
                    lastFullIncrement = target - currentBet;
                }
                currentBet = target;
                for (Seat other : seats) {
                    if (other.active() && other.streetPaid < target) other.pending = true;
                }
            }
        }
        seat.lastActionWager = currentBet;
        seat.pending = false;
        history.add(Event.action(history.size(), street, seat.index, action.type(), paid,
                seat.streetPaid, !seat.folded && seat.stack == 0, fallback));
        cursor = nextSeat(seat.index);
        pendingDecision = null;
    }

    HandResult result(int heroSeat) {
        if (resultPlayers == null) throw new IllegalStateException("Hand has not finished");
        Seat hero = seats[heroSeat];
        return new HandResult(handId(), number, heroSeat, button, hero.startingStack,
                Cards.format(hero.hole, 2), Cards.format(board, boardSize), resultReason,
                resultPlayers, settledPots, List.copyOf(history));
    }

    private Observation observe(Seat hero, int active) {
        var players = new ArrayList<Player>(seats.length);
        int pot = 0;
        for (Seat seat : seats) {
            pot += seat.handPaid;
            players.add(new Player(seat.index, seat.alias(), seat.startingStack, seat.stack,
                    seat.streetPaid, seat.handPaid, seat.folded ? "folded" : seat.stack == 0 ? "all_in" : "active"));
        }
        int toCall = currentBet - hero.streetPaid;
        var actions = new ArrayList<LegalAction>(3);
        actions.add(LegalAction.simple(ActionType.FOLD));
        if (toCall == 0) actions.add(LegalAction.simple(ActionType.CHECK));
        else actions.add(LegalAction.call(Math.min(toCall, hero.stack)));
        int capacity = hero.streetPaid + hero.stack;
        boolean reopened = hero.lastActionWager < 0 || currentBet - hero.lastActionWager >= lastFullIncrement;
        if (active >= 2 && capacity > currentBet && reopened) {
            long fullTarget = currentBet < bigBlind ? bigBlind : (long) currentBet + lastFullIncrement;
            actions.add(LegalAction.aggressive(currentBet == 0 ? ActionType.BET : ActionType.RAISE,
                    (int) Math.min(fullTarget, capacity), capacity));
        }
        return new Observation(handId(), number, street, hero.index, button, smallBlind, bigBlind,
                hero.startingStack, Cards.format(hero.hole, 2), Cards.format(board, boardSize),
                List.copyOf(players), pot, List.copyOf(layers()), currentBet, toCall,
                List.copyOf(actions), List.copyOf(history));
    }

    private List<Pot> layers() {
        var levels = new TreeSet<Integer>();
        for (Seat seat : seats) if (seat.handPaid > 0) levels.add(seat.handPaid);
        var pots = new ArrayList<Pot>(levels.size());
        int previous = 0;
        for (int level : levels) {
            int contributors = 0;
            var eligible = new ArrayList<Integer>();
            for (Seat seat : seats) {
                if (seat.handPaid >= level) {
                    contributors++;
                    if (!seat.folded) eligible.add(seat.index);
                }
            }
            if (eligible.isEmpty()) throw new IllegalStateException("Pot layer has no eligible players");
            pots.add(new Pot((level - previous) * contributors, List.copyOf(eligible)));
            previous = level;
        }
        return pots;
    }

    private void refundUncalled() {
        Seat leader = null;
        int highest = 0;
        int second = 0;
        for (Seat seat : seats) {
            if (seat.streetPaid > highest) {
                second = highest;
                highest = seat.streetPaid;
                leader = seat;
            } else {
                second = Math.max(second, seat.streetPaid);
            }
        }
        if (highest > second) {
            int refund = highest - second;
            leader.commit(-refund);
            history.add(Event.refund(history.size(), street, leader.index, refund, leader.streetPaid));
        }
    }

    private void dealStreet() {
        street = switch (street) {
            case PREFLOP -> Street.FLOP;
            case FLOP -> Street.TURN;
            case TURN -> Street.RIVER;
            case RIVER -> throw new IllegalStateException("There is no street after the river");
        };
        currentBet = 0;
        lastFullIncrement = bigBlind;
        cursor = nextSeat(button);
        for (Seat seat : seats) {
            seat.streetPaid = 0;
            seat.lastActionWager = -1;
            seat.pending = seat.active();
        }
        nextCard++; // Burn one card before revealing this street.
        int count = street == Street.FLOP ? 3 : 1;
        int[] revealed = new int[count];
        for (int i = 0; i < count; i++) {
            revealed[i] = deck[nextCard++];
            board[boardSize++] = revealed[i];
        }
        history.add(Event.board(history.size(), street, Cards.format(revealed, count)));
    }

    private void finish(boolean showdown) {
        var payable = new ArrayList<Pot>();
        for (Pot layer : layers()) {
            // A folded player's contribution can create a level without creating a separate side pot.
            int last = payable.size() - 1;
            if (last >= 0 && payable.get(last).eligibleSeats().equals(layer.eligibleSeats())) {
                payable.set(last, new Pot(payable.get(last).amount() + layer.amount(), layer.eligibleSeats()));
            } else {
                payable.add(layer);
            }
        }
        int[] ranks = new int[seats.length];
        if (showdown) {
            for (Seat seat : seats) {
                if (seat.folded) continue;
                int[] cards = Arrays.copyOf(board, 7);
                cards[5] = seat.hole[0];
                cards[6] = seat.hole[1];
                ranks[seat.index] = HandRank.bestOfSeven(cards);
            }
        }
        var payouts = new ArrayList<SettledPot>(payable.size());
        for (Pot pot : payable) {
            int best = pot.eligibleSeats().stream().mapToInt(seat -> ranks[seat]).max().orElseThrow();
            var winners = new ArrayList<Integer>();
            for (int offset = 1; offset <= seats.length; offset++) {
                int seat = (button + offset) % seats.length;
                if (pot.eligibleSeats().contains(seat) && ranks[seat] == best) winners.add(seat);
            }
            int share = pot.amount() / winners.size();
            int remainder = pot.amount() % winners.size();
            var awards = new ArrayList<Award>();
            for (int i = 0; i < winners.size(); i++) {
                int amount = share + (i < remainder ? 1 : 0);
                int seat = winners.get(i);
                seats[seat].stack += amount;
                if (amount > 0) awards.add(new Award(seat, amount));
            }
            payouts.add(new SettledPot(pot.amount(), pot.eligibleSeats(), List.copyOf(awards)));
        }
        if (Arrays.stream(seats).mapToLong(seat -> seat.stack).sum() != totalChips) {
            throw new IllegalStateException("Hand did not conserve chips");
        }
        var players = new ArrayList<ResultPlayer>(seats.length);
        for (Seat seat : seats) {
            players.add(new ResultPlayer(seat.index, seat.alias(), seat.startingStack, seat.stack,
                    seat.stack - seat.startingStack,
                    showdown && !seat.folded ? Cards.format(seat.hole, 2) : List.of()));
        }
        settledPots = List.copyOf(payouts);
        resultPlayers = List.copyOf(players);
        resultReason = showdown ? "showdown" : "all_folded";
    }

    private String handId() {
        return "h" + number;
    }

    private int activeCount() {
        int count = 0;
        for (Seat seat : seats) if (seat.active()) count++;
        return count;
    }

    private int nextSeat(int seat) {
        return (seat + 1) % seats.length;
    }

    private void postBlind(int index, String kind, int amount) {
        Seat seat = seats[index];
        seat.commit(amount);
        history.add(Event.blind(history.size(), street, index, kind, amount, seat.streetPaid));
    }

    private static final class Seat {
        final int index;
        final int startingStack;
        final int[] hole = new int[2];
        int stack;
        int streetPaid;
        int handPaid;
        int lastActionWager = -1;
        boolean pending;
        boolean folded;

        Seat(int index, int startingStack) {
            this.index = index;
            this.startingStack = startingStack;
            this.stack = startingStack;
        }

        void commit(int amount) {
            stack -= amount;
            streetPaid += amount;
            handPaid += amount;
        }

        boolean active() {
            return !folded && stack > 0;
        }

        String alias() {
            return "seat-" + (index + 1);
        }
    }
}

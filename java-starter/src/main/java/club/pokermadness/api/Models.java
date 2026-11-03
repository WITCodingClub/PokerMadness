package club.pokermadness.api;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Public immutable data structures. Component names are camelCase in Java and snake_case on the wire. */
public final class Models {
    private Models() {}

    public enum ActionType {
        @SerializedName("fold") FOLD,
        @SerializedName("check") CHECK,
        @SerializedName("call") CALL,
        @SerializedName("bet") BET,
        @SerializedName("raise") RAISE
    }

    public enum Street {
        @SerializedName("preflop") PREFLOP,
        @SerializedName("flop") FLOP,
        @SerializedName("turn") TURN,
        @SerializedName("river") RIVER
    }

    public enum PlayerStatus {
        @SerializedName("active") ACTIVE,
        @SerializedName("folded") FOLDED,
        @SerializedName("all_in") ALL_IN
    }

    public record Player(int seat, String playerId, int startingStack, int stack,
                         int streetContribution, int handContribution, PlayerStatus status) {
        public Player { Objects.requireNonNull(playerId); Objects.requireNonNull(status); }
    }

    public record Pot(int amount, List<Integer> eligibleSeats) {
        public Pot { eligibleSeats = List.copyOf(eligibleSeats); }
    }

    public record LegalAction(ActionType action, Integer amount, Integer minAmount, Integer maxAmount) {
        public LegalAction { Objects.requireNonNull(action); }
        public boolean isAggressive() { return action == ActionType.BET || action == ActionType.RAISE; }
        public boolean allowsTarget(int target) {
            return isAggressive() && target >= minAmount && target <= maxAmount;
        }
    }

    /** Nullable fields appear only on their corresponding event variant. */
    public record HistoryEvent(int eventId, Street street, String type, Integer seat,
                               String blind, ActionType action, Integer amount,
                               Integer streetTotal, Boolean allIn, Boolean fallback, List<Card> cards) {
        public HistoryEvent {
            Objects.requireNonNull(street); Objects.requireNonNull(type);
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    public record Observation(String handId, int handNumber, Street street, int heroSeat,
                              int buttonSeat, int smallBlind, int bigBlind, int startingStack,
                              List<Card> holeCards, List<Card> board, List<Player> players,
                              int pot, List<Pot> pots, int currentBet, int toCall,
                              List<LegalAction> legalActions, List<HistoryEvent> history) {
        public Observation {
            Objects.requireNonNull(handId); Objects.requireNonNull(street);
            holeCards = List.copyOf(holeCards); board = List.copyOf(board);
            players = List.copyOf(players); pots = List.copyOf(pots);
            legalActions = List.copyOf(legalActions); history = List.copyOf(history);
        }
        public Player hero() { return players.get(heroSeat); }
        public Optional<LegalAction> legal(ActionType action) {
            return legalActions.stream().filter(x -> x.action() == action).findFirst();
        }
        public boolean can(ActionType action) { return legal(action).isPresent(); }
        public Action safeFallback() { return can(ActionType.CHECK) ? Action.check() : Action.fold(); }
    }

    public record ResultPlayer(int seat, String playerId, int startingStack, int finalStack,
                               int netChips, List<Card> shownCards) {
        public ResultPlayer { Objects.requireNonNull(playerId); shownCards = List.copyOf(shownCards); }
    }

    public record Award(int seat, int amount) {}

    public record SettledPot(int amount, List<Integer> eligibleSeats, List<Award> awards) {
        public SettledPot { eligibleSeats = List.copyOf(eligibleSeats); awards = List.copyOf(awards); }
    }

    public record HandResult(String handId, int handNumber, int heroSeat, int buttonSeat,
                             int startingStack, List<Card> holeCards, List<Card> board, String reason,
                             List<ResultPlayer> players, List<SettledPot> pots, List<HistoryEvent> history) {
        public HandResult {
            Objects.requireNonNull(handId); Objects.requireNonNull(reason);
            holeCards = List.copyOf(holeCards); board = List.copyOf(board); players = List.copyOf(players);
            pots = List.copyOf(pots); history = List.copyOf(history);
        }
        public ResultPlayer hero() { return players.get(heroSeat); }
    }
}

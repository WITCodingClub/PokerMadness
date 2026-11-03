package club.pokermadness.engine;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** These are the only game snapshots exposed to a bot. */
final class TableView {
    private TableView() {}

    enum ActionType {
        @SerializedName("fold") FOLD,
        @SerializedName("check") CHECK,
        @SerializedName("call") CALL,
        @SerializedName("bet") BET,
        @SerializedName("raise") RAISE;

        boolean isAggressive() {
            return this == BET || this == RAISE;
        }
    }

    enum Street {
        @SerializedName("preflop") PREFLOP,
        @SerializedName("flop") FLOP,
        @SerializedName("turn") TURN,
        @SerializedName("river") RIVER
    }

    record Action(ActionType type, Integer amount) {
        static Action simple(ActionType type) {
            return new Action(type, null);
        }
    }

    record LegalAction(ActionType action, Integer amount, Integer minAmount, Integer maxAmount) {
        static LegalAction simple(ActionType action) {
            return new LegalAction(action, null, null, null);
        }

        static LegalAction call(int amount) {
            return new LegalAction(ActionType.CALL, amount, null, null);
        }

        static LegalAction aggressive(ActionType action, int min, int max) {
            return new LegalAction(action, null, min, max);
        }

        boolean allows(Action candidate) {
            if (action != candidate.type()) return false;
            if (!action.isAggressive()) return candidate.amount() == null;
            return candidate.amount() != null && candidate.amount() >= minAmount
                    && candidate.amount() <= maxAmount;
        }
    }

    record Player(int seat, String playerId, int startingStack, int stack,
                  int streetContribution, int handContribution, String status) {}
    record Pot(int amount, List<Integer> eligibleSeats) {}
    record Award(int seat, int amount) {}
    record SettledPot(int amount, List<Integer> eligibleSeats, List<Award> awards) {}
    record ResultPlayer(int seat, String playerId, int startingStack, int finalStack,
                        int netChips, List<String> shownCards) {}

    record Event(int eventId, Street street, String type, Integer seat, String blind,
                 ActionType action, Integer amount, Integer streetTotal, Boolean allIn,
                 Boolean fallback, List<String> cards) {
        static Event blind(int id, Street street, int seat, String blind, int amount, int total) {
            return new Event(id, street, "post_blind", seat, blind, null, amount, total, null, null, null);
        }

        static Event action(int id, Street street, int seat, ActionType action, int amount,
                            int total, boolean allIn, boolean fallback) {
            return new Event(id, street, "action", seat, null, action, amount, total, allIn, fallback, null);
        }

        static Event refund(int id, Street street, int seat, int amount, int total) {
            return new Event(id, street, "return_uncalled", seat, null, null, amount, total, null, null, null);
        }

        static Event board(int id, Street street, List<String> cards) {
            return new Event(id, street, "deal_board", null, null, null, null, null, null, null, cards);
        }
    }

    record Observation(String handId, int handNumber, Street street, int heroSeat, int buttonSeat,
                       int smallBlind, int bigBlind, int startingStack, List<String> holeCards,
                       List<String> board, List<Player> players, int pot, List<Pot> pots,
                       int currentBet, int toCall, List<LegalAction> legalActions, List<Event> history) {
        boolean allows(Action candidate) {
            return legalActions.stream().anyMatch(legal -> legal.allows(candidate));
        }

        Action fallback() {
            Action check = Action.simple(ActionType.CHECK);
            return allows(check) ? check : Action.simple(ActionType.FOLD);
        }
    }

    record HandResult(String handId, int handNumber, int heroSeat, int buttonSeat, int startingStack,
                      List<String> holeCards, List<String> board, String reason,
                      List<ResultPlayer> players, List<SettledPot> pots, List<Event> history) {}
}

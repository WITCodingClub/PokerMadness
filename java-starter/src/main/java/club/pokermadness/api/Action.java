package club.pokermadness.api;

import java.util.Objects;
import static club.pokermadness.api.Models.ActionType;

/** Bet/raise amounts are target totals for the current street, not added chips. */
public record Action(ActionType action, Integer amount) {
    public Action {
        Objects.requireNonNull(action);
        boolean aggressive = action == ActionType.BET || action == ActionType.RAISE;
        if (aggressive != (amount != null) || (amount != null && amount <= 0)) {
            throw new IllegalArgumentException("Only bet/raise need a positive target amount");
        }
    }
    public static Action fold() { return new Action(ActionType.FOLD, null); }
    public static Action check() { return new Action(ActionType.CHECK, null); }
    public static Action call() { return new Action(ActionType.CALL, null); }
    public static Action betTo(int total) { return new Action(ActionType.BET, total); }
    public static Action raiseTo(int total) { return new Action(ActionType.RAISE, total); }
}

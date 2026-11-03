package club.pokermadness.api;

import com.google.gson.JsonObject;
import java.util.Objects;

public record Decision(Action action, JsonObject userState) {
    public Decision {
        Objects.requireNonNull(action); Objects.requireNonNull(userState);
        userState = userState.deepCopy();
    }
}

package club.pokermadness.engine;

/** An unsuccessful bot exchange; the caller decides which fallback applies. */
final class BotFailure extends Exception {
    BotFailure(String message) {
        super(message);
    }

    BotFailure(String message, Throwable cause) {
        super(message, cause);
    }
}

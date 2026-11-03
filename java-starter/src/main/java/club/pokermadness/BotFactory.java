package club.pokermadness;

import club.pokermadness.api.PokerBot;
import club.pokermadness.bot.StarterBot;

/** Change this one constructor if you give your strategy class a different name. */
public final class BotFactory {
    private BotFactory() {}
    public static PokerBot create() { return new StarterBot(); }
}

package club.pokermadness.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/** Card IDs are suit * 13 + rank offset. Only their string form crosses the wire. */
final class Cards {
    private static final String RANKS = "23456789TJQKA";
    private static final String SUITS = "cdhs";
    private static final int[] STANDARD_DECK = new int[52];

    static {
        Arrays.setAll(STANDARD_DECK, i -> i);
    }

    private Cards() {}

    static int[] shuffled(RandomGenerator random) {
        int[] cards = STANDARD_DECK.clone();
        for (int i = cards.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temporary = cards[i];
            cards[i] = cards[j];
            cards[j] = temporary;
        }
        return cards;
    }

    static int parse(String card) {
        if (card.length() != 2) {
            throw new IllegalArgumentException("Invalid card: " + card);
        }
        int rank = RANKS.indexOf(card.charAt(0));
        int suit = SUITS.indexOf(card.charAt(1));
        if (rank < 0 || suit < 0) {
            throw new IllegalArgumentException("Invalid card: " + card);
        }
        return suit * 13 + rank;
    }

    static String format(int card) {
        return "" + RANKS.charAt(card % 13) + SUITS.charAt(card / 13);
    }

    static int rank(int card) {
        return card % 13 + 2;
    }

    static int suit(int card) {
        return card / 13;
    }

    static List<String> format(int[] cards, int length) {
        var result = new ArrayList<String>(length);
        for (int i = 0; i < length; i++) {
            result.add(format(cards[i]));
        }
        return Collections.unmodifiableList(result);
    }
}

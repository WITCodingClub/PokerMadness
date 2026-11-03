package club.pokermadness.api;

/** Immutable card. Wire notation uses As, Kc, Td, etc. */
public record Card(int rank, char suit) {
    private static final String RANKS = "23456789TJQKA";

    public Card {
        if (rank < 2 || rank > 14 || "cdhs".indexOf(suit) < 0) {
            throw new IllegalArgumentException("Invalid rank or suit");
        }
    }

    public static Card parse(String text) {
        if (text == null || text.length() != 2) {
            throw new IllegalArgumentException("A card must have two characters, for example As");
        }
        int index = RANKS.indexOf(text.charAt(0));
        if (index < 0) throw new IllegalArgumentException("Invalid card rank: " + text);
        return new Card(index + 2, text.charAt(1));
    }

    @Override public String toString() {
        return "" + RANKS.charAt(rank - 2) + suit;
    }
}

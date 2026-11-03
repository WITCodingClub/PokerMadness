package club.pokermadness.engine;

/** Scores the best five cards of seven. Higher scores always win; suits never break ties. */
final class HandRank {
    private HandRank() {}

    static int bestOfSeven(int[] cards) {
        if (cards.length != 7) {
            throw new IllegalArgumentException("Expected seven cards");
        }
        int best = 0;
        int[] count = new int[15];
        for (int a = 0; a < 3; a++) {
            for (int b = a + 1; b < 4; b++) {
                for (int c = b + 1; c < 5; c++) {
                    for (int d = c + 1; d < 6; d++) {
                        for (int e = d + 1; e < 7; e++) {
                            best = Math.max(best, five(cards[a], cards[b], cards[c], cards[d], cards[e], count));
                        }
                    }
                }
            }
        }
        return best;
    }

    private static int five(int a, int b, int c, int d, int e, int[] count) {
        int ar = Cards.rank(a), br = Cards.rank(b), cr = Cards.rank(c), dr = Cards.rank(d), er = Cards.rank(e);
        count[ar]++;
        count[br]++;
        count[cr]++;
        count[dr]++;
        count[er]++;
        int suit = Cards.suit(a);
        boolean flush = suit == Cards.suit(b) && suit == Cards.suit(c)
                && suit == Cards.suit(d) && suit == Cards.suit(e);
        int rankMask = 0, kickers = 0, four = 0, three = 0, highPair = 0, lowPair = 0;
        for (int rank = 14; rank >= 2; rank--) {
            switch (count[rank]) {
                case 4 -> four = rank;
                case 3 -> three = rank;
                case 2 -> {
                    if (highPair == 0) highPair = rank;
                    else lowPair = rank;
                }
                case 1 -> kickers = (kickers << 4) | rank;
                default -> { }
            }
            if (count[rank] != 0) rankMask |= 1 << rank;
            count[rank] = 0;
        }

        int straight = 0;
        if (four == 0 && three == 0 && highPair == 0) {
            if ((rankMask & (1 << 14)) != 0) rankMask |= 1 << 1;
            for (int high = 14; high >= 5; high--) {
                if (((rankMask >> (high - 4)) & 31) == 31) {
                    straight = high;
                    break;
                }
            }
        }
        if (flush && straight > 0) return score(8, straight);
        if (four > 0) return score(7, (four << 4) | kickers);
        if (three > 0 && highPair > 0) return score(6, (three << 4) | highPair);
        if (flush) return score(5, kickers);
        if (straight > 0) return score(4, straight);
        if (three > 0) return score(3, (three << 8) | kickers);
        if (lowPair > 0) return score(2, (highPair << 8) | (lowPair << 4) | kickers);
        if (highPair > 0) return score(1, (highPair << 12) | kickers);
        return score(0, kickers);
    }

    private static int score(int category, int tiebreakers) {
        return (category << 20) | tiebreakers;
    }
}

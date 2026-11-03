package club.pokermadness.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandRankTest {
    @Test
    void handCategoriesIncreaseInPokerOrder() {
        List<String> hands = List.of(
                "As Kd Jh 9c 7s 4h 2d",
                "As Ad Jh 9c 7s 4h 2d",
                "As Ad Jh Jc 7s 4h 2d",
                "As Ad Ah Jc 7s 4h 2d",
                "As Kd Qh Jc Ts 4h 2d",
                "As Js 9s 7s 4s Kd 2d",
                "As Ad Ah Jc Js 4h 2d",
                "As Ad Ah Ac Js 4h 2d",
                "As Ks Qs Js Ts 4h 2d"
        );
        for (int i = 1; i < hands.size(); i++) assertTrue(rank(hands.get(i)) > rank(hands.get(i - 1)));
    }

    @Test
    void wheelsKickersAndTwoTriplesBreakTiesCorrectly() {
        assertTrue(rank("2s 3d 4h 5c 6c Kd Qh") > rank("As 2d 3h 4c 5c Kd Qh"));
        assertTrue(rank("As Ad Ac Ah Ks 4h 2d") > rank("As Ad Ac Ah Qs 4h 2d"));
        assertEquals(rank("As Ad Ac Ks Kd 4h 2d"), rank("As Ad Ac Ks Kd Kh 2d"));
        assertEquals(rank("Th Jh Qh Kh Ah 2s 3d"), rank("Th Jh Qh Kh Ah 4s 5d"));
    }

    private static int rank(String cards) {
        return HandRank.bestOfSeven(Arrays.stream(cards.split(" ")).mapToInt(Cards::parse).toArray());
    }
}

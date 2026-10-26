package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocialActionPresenterTest {
    @Test
    void rendersStructuredActionWithoutRecomputingItsOutcome() {
        var result = new EconomyV2Client.SocialActionResult(null, "hug", "123456789012345678",
            "456789012345678901", "none", 86_400_000, 0, 0);
        assertEquals("<@123456789012345678> hugs <@456789012345678901>.",
            SocialActionPresenter.success(Language.EN, result));
        assertEquals("You can do that again in 23h 42m.",
            SocialActionPresenter.cooldown(Language.EN, (23 * 60L + 42) * 60_000));
    }
}

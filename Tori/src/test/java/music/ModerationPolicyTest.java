package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModerationPolicyTest {
    @Test void permitsLowerRankedTarget() {
        assertDoesNotThrow(() -> ModerationPolicy.target(1, 2, 3, false, false, true, true, true));
    }
    @Test void deniesSelfModeration() {
        assertThrows(IllegalArgumentException.class, () -> ModerationPolicy.target(1, 1, 3, false, false, true, true, false));
    }
    @Test void protectsBot() {
        assertThrows(IllegalArgumentException.class, () -> ModerationPolicy.target(1, 3, 3, false, false, true, true, false));
    }
    @Test void protectsOwner() {
        assertThrows(IllegalArgumentException.class, () -> ModerationPolicy.target(1, 2, 3, true, false, true, true, false));
    }
    @Test void rejectsEqualOrHigherActorRole() {
        assertThrows(IllegalArgumentException.class, () -> ModerationPolicy.target(1, 2, 3, false, false, false, true, false));
    }
    @Test void rejectsEqualOrHigherBotRole() {
        assertThrows(IllegalArgumentException.class, () -> ModerationPolicy.target(1, 2, 3, false, false, true, false, false));
    }
    @Test void protectsAdministratorsFromTimeout() {
        assertThrows(IllegalArgumentException.class, () -> ModerationPolicy.target(1, 2, 3, false, true, true, true, true));
    }
    @Test void allowsAdministratorKickWhenBothRolesAreHigher() {
        assertDoesNotThrow(() -> ModerationPolicy.target(1, 2, 3, false, true, true, true, false));
    }
}

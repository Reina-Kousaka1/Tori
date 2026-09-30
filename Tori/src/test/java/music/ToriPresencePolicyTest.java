package music;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.entities.Activity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToriPresencePolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void generalFallbackHasOnlyGeneralTemplatesAndPreservesTheShardCount() {
        var context = ToriPresenceContext.general();
        assertTrue(ToriPresencePolicy.candidateTexts(context).contains("Busy at school 🏐🎀"));
        var resolved = ToriPresencePolicy.resolve(context, 3, bound -> 0, null);
        assertEquals(Activity.ActivityType.PLAYING, resolved.getType());
        assertEquals("Busy at school 🏐🎀 | (3)", resolved.getName());
    }

    @Test void activityPoolsNeverSelectAnotherSport() {
        assertCompatible(ToriPresenceContext.Activity.BALLET,
            new ToriPresenceContext(ToriPresenceContext.Activity.BALLET, ToriPresenceContext.Mood.NORMAL,
                ToriPresenceContext.Season.NONE, null, 0, 1, null, null),
            List.of("barre", "rehearsal", "pirouettes", "ballet practice", "Stretching"));
        assertCompatible(ToriPresenceContext.Activity.VOLLEYBALL,
            context(ToriPresenceContext.Activity.VOLLEYBALL),
            List.of("Volleyball", "serves", "Captain", "Game day", "practice"));
        assertCompatible(ToriPresenceContext.Activity.CHEER,
            context(ToriPresenceContext.Activity.CHEER),
            List.of("cheer", "Competition", "routine"));
    }

    @Test void moodAddsActivitySpecificCopyWithoutReplacingTheActivity() {
        var ballet = new ToriPresenceContext(ToriPresenceContext.Activity.BALLET,
            ToriPresenceContext.Mood.SLEEPY, ToriPresenceContext.Season.NONE, null, 0.8, 2, null, null);
        var choices = ToriPresencePolicy.candidateTexts(ballet);
        assertTrue(choices.contains("Surviving morning rehearsal 🌙"));
        assertTrue(choices.stream().noneMatch(text -> text.toLowerCase().contains("volleyball")));
        assertEquals("Surviving morning rehearsal 🌙 | (1)",
            ToriPresencePolicy.resolve(ballet, 1, bound -> bound - 1, null).getName());
    }

    @Test void seasonAddsAnActivityCompatibleTemplate() {
        var volleyball = new ToriPresenceContext(ToriPresenceContext.Activity.VOLLEYBALL,
            ToriPresenceContext.Mood.NORMAL, ToriPresenceContext.Season.SUMMER,
            null, 0, 3, null, null);
        var choices = ToriPresencePolicy.candidateTexts(volleyball);
        assertTrue(choices.contains("Summer serves after school 🏐☀️"));
        assertTrue(choices.stream().noneMatch(text -> text.contains("ballet")));
        assertEquals("Summer serves after school 🏐☀️ | (2)",
            ToriPresencePolicy.resolve(volleyball, 2, bound -> bound - 1, null).getName());
    }

    @Test void specialEventOutranksTheCurrentActivityAndMood() {
        var context = new ToriPresenceContext(ToriPresenceContext.Activity.BALLET,
            ToriPresenceContext.Mood.FOCUSED, ToriPresenceContext.Season.AUTUMN,
            "game_day", 0.9, 4, null, null);
        assertEquals(List.of("Game day. Captain mode 🏐", "Ready to serve up a win 🏐"),
            ToriPresencePolicy.candidateTexts(context));
        assertEquals("Game day. Captain mode 🏐 | (4)",
            ToriPresencePolicy.resolve(context, 4, bound -> 0, null).getName());
    }

    @Test void eventSeasonValuesAlsoUseTheHighPriorityEventTemplates() {
        var christmas = new ToriPresenceContext(ToriPresenceContext.Activity.VOLLEYBALL,
            ToriPresenceContext.Mood.SLEEPY, ToriPresenceContext.Season.CHRISTMAS,
            null, 0.8, 5, null, null);
        assertEquals(List.of("Making a Christmas wish list ✨🎄",
            "Holiday break after practice 🎄"), ToriPresencePolicy.candidateTexts(christmas));
    }

    @Test void futureUnknownValuesDegradeToGeneralSafeDefaults() throws Exception {
        var json = JSON.readTree("""
            {"schema_version":2,"activity":"new_future_activity","mood":"mysterious",
             "season":"monsoon","special_event":"future_event","intensity":0.4,"revision":9}
            """);
        var context = ToriPresenceContext.fromJson(json);
        assertEquals(ToriPresenceContext.Activity.GENERAL, context.activity());
        assertEquals(ToriPresenceContext.Mood.NORMAL, context.mood());
        assertEquals(ToriPresenceContext.Season.NONE, context.season());
        assertEquals(List.of("Busy at school 🏐🎀", "Doing homework... unfortunately",
            "Getting ready", "Probably shopping", "Taking a little break"),
            ToriPresencePolicy.candidateTexts(context));
    }

    @Test void malformedContextVersionsAndFieldsAreRejected() throws Exception {
        var wrongVersion = JSON.readTree("""
            {"schema_version":2.5,"activity":"ballet"}
            """);
        var wrongIntensity = JSON.readTree("""
            {"schema_version":2,"activity":"ballet","intensity":"high"}
            """);
        assertThrows(IllegalArgumentException.class, () -> ToriPresenceContext.fromJson(wrongVersion));
        assertThrows(IllegalArgumentException.class, () -> ToriPresenceContext.fromJson(wrongIntensity));
    }

    @Test void statusSelectionIsDeterministicWithAnInjectedPicker() {
        var context = context(ToriPresenceContext.Activity.CHEER);
        var first = ToriPresencePolicy.resolve(context, 2, bound -> bound - 1, null);
        var second = ToriPresencePolicy.resolve(context, 2, bound -> bound - 1, null);
        assertEquals(first.getName(), second.getName());
        assertEquals(Activity.ActivityType.CUSTOM_STATUS, first.getType());
    }

    private static void assertCompatible(ToriPresenceContext.Activity activity,
                                         ToriPresenceContext context, List<String> markers) {
        assertEquals(activity, context.activity());
        var choices = ToriPresencePolicy.candidateTexts(context);
        assertTrue(choices.stream().allMatch(text -> markers.stream()
            .anyMatch(marker -> text.toLowerCase().contains(marker.toLowerCase()))), choices.toString());
    }

    private static ToriPresenceContext context(ToriPresenceContext.Activity activity) {
        return new ToriPresenceContext(activity, ToriPresenceContext.Mood.NORMAL,
            ToriPresenceContext.Season.NONE, null, 0, 1, Instant.EPOCH, null);
    }
}

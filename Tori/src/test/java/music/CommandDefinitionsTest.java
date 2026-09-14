package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandDefinitionsTest {
    @Test void allSlashCommandsSerializeWithValidOptions() {
        var commands = CommandRegistration.definitions();
        assertEquals(26, commands.size());
        assertEquals(26, commands.stream().map(c -> c.getName()).distinct().count());
        commands.forEach(command -> assertDoesNotThrow(() -> command.toData().toString(), command.getName()));
    }
    @Test void statusExposesRotationActionsAndBoundedMillisecondInterval() throws Exception {
        var command = GeneralBot.commands().stream().filter(c -> c.getName().equals("status")).findFirst().orElseThrow();
        var json = new ObjectMapper().readTree(command.toData().toString());
        Map<String, JsonNode> options = new HashMap<>();
        json.path("options").forEach(option -> options.put(option.path("name").asText(), option));
        assertEquals(Set.of("action", "texts", "interval_ms"), options.keySet());
        options.values().forEach(option -> assertFalse(option.path("required").asBoolean()));
        Set<String> actions = new HashSet<>();
        options.get("action").path("choices").forEach(choice -> actions.add(choice.path("value").asText()));
        assertEquals(Set.of("start", "stop", "show"), actions);
        assertEquals(StatusRotation.MIN_INTERVAL_MS, options.get("interval_ms").path("min_value").asLong());
        assertEquals(StatusRotation.MAX_INTERVAL_MS, options.get("interval_ms").path("max_value").asLong());
    }
}

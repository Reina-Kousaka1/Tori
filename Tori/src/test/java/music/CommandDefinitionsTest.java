package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandDefinitionsTest {
    @Test void allSlashCommandsSerializeWithValidOptions() {
        var commands = CommandRegistration.definitions();
        assertEquals(30, commands.size());
        assertEquals(30, commands.stream().map(c -> c.getName()).distinct().count());
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
    @Test void orderCommandsExposeRequiredServiceActionsAndTicketCategories() throws Exception {
        var order=TicketOrderBot.commands().stream().filter(c->c.getName().equals("order")).findFirst().orElseThrow();
        var ticket=TicketOrderBot.commands().stream().filter(c->c.getName().equals("ticket")).findFirst().orElseThrow();
        assertEquals(Set.of("create","view","queue","assign","edit","cancel","history","post","config"),
            ((SlashCommandData)order).getSubcommands().stream().map(s->s.getName()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("panel","config","reset_text","open","details","claim","close","reopen","transcript","add","remove","link_order"),
            ((SlashCommandData)ticket).getSubcommands().stream().map(s->s.getName()).collect(java.util.stream.Collectors.toSet()));
        var ticketConfig=((SlashCommandData)ticket).getSubcommands().stream().filter(s->s.getName().equals("config")).findFirst().orElseThrow();
        assertEquals(Set.of("staff_role","category","panel_title","panel_text","button_text","welcome_text",
                "claim_button","close_button","delete_button"),
            ticketConfig.getOptions().stream().map(o->o.getName()).collect(java.util.stream.Collectors.toSet()));
        var transcript=((SlashCommandData)ticket).getSubcommands().stream().filter(s->s.getName().equals("transcript")).findFirst().orElseThrow();
        assertEquals("id",transcript.getOptions().get(0).getName());
        assertFalse(transcript.getOptions().get(0).isRequired());
        var orderJson=new ObjectMapper().readTree(order.toData().toString());
        JsonNode create=null; for(var option:orderJson.path("options"))if(option.path("name").asText().equals("create"))create=option;
        assertNotNull(create);
        Set<String> required=new HashSet<>(); for(var option:create.path("options"))if(option.path("required").asBoolean())required.add(option.path("name").asText());
        assertEquals(Set.of("customer","product","description"),required);
        Set<String> allCreateOptions=new HashSet<>();
        for(var option:create.path("options"))allCreateOptions.add(option.path("name").asText());
        assertEquals(Set.of("customer","product","description","assigned_staff"),allCreateOptions);
    }
}

import com.brandopakel.remy.playerengine.brain.RemyBrain;
import com.brandopakel.remy.playerengine.brain.RemyBrain.Intent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Plain-Java checks for RemyBrain's pure logic (no Minecraft bootstrap needed). */
public class RemyBrainCheck {
    static int failures = 0;

    static void eq(Object actual, Object expected, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> got <" + actual + ">");
        } else {
            System.out.println("ok   " + what);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // Intents from plain rules
        eq(RemyBrain.ruleIntent("stop"), Intent.STOP, "stop");
        eq(RemyBrain.ruleIntent("Stop that right now"), Intent.STOP, "stop sentence");
        eq(RemyBrain.ruleIntent("follow me buddy"), Intent.FOLLOW, "follow");
        eq(RemyBrain.ruleIntent("protect me from the zombies"), Intent.DEFEND, "protect beats zombie attack");
        eq(RemyBrain.ruleIntent("kill all the monsters"), Intent.CLEAR_HOSTILES, "clear hostiles");
        eq(RemyBrain.ruleIntent("attack that creeper"), Intent.ATTACK, "attack");
        eq(RemyBrain.ruleIntent("chop 8 wood"), Intent.GATHER, "chop wood");
        eq(RemyBrain.ruleIntent("mine some iron"), Intent.MINE, "mine iron");
        eq(RemyBrain.ruleIntent("set up a farm here"), Intent.SETUP_FARM, "farm");
        eq(RemyBrain.ruleIntent("give me 5 bread"), Intent.GIVE, "give");
        eq(RemyBrain.ruleIntent("build me a little house"), Intent.BUILD, "build");
        eq(RemyBrain.ruleIntent("we should probably do something about dinner"), null, "fuzzy -> model");

        // Counts
        eq(RemyBrain.parseCount("chop 8 wood", 16), 8, "count digits");
        eq(RemyBrain.parseCount("get a stack of torches", 16), 64, "count stack");
        eq(RemyBrain.parseCount("get two stacks of cobble", 16), 128, "count two stacks");
        eq(RemyBrain.parseCount("grab a few apples", 16), 4, "count a few");
        eq(RemyBrain.parseCount("get wood", 16), 16, "count default");

        // Registry matching (synthetic registry incl. a modded item)
        List<Identifier> items = List.of(new Identifier("minecraft:oak_log"), new Identifier("minecraft:birch_log"),
                new Identifier("minecraft:iron_ingot"), new Identifier("minecraft:iron_ore"),
                new Identifier("minecraft:torch"), new Identifier("create:andesite_alloy"),
                new Identifier("farmersdelight:tomato"), new Identifier("minecraft:bread"));
        eq(RemyBrain.match("chop some birch logs", items, Map.of(), 3).get(0), "minecraft:birch_log", "birch logs");
        eq(RemyBrain.match("get me 4 andesite alloy", items, Map.of(), 3).get(0), "create:andesite_alloy", "modded by name");
        eq(RemyBrain.match("get create:andesite_alloy", items, Map.of(), 3).get(0), "create:andesite_alloy", "modded by id");
        eq(RemyBrain.match("pick tomatoes", items, Map.of(), 3).get(0), "farmersdelight:tomato", "plural modded");
        eq(RemyBrain.match("bring torches", items, Map.of(), 3).get(0), "minecraft:torch", "torches -> torch");
        eq(RemyBrain.match("get wood", items, Map.of("wood", "minecraft:oak_log"), 3).get(0), "minecraft:oak_log", "synonym");

        // Plans
        Method toPlan = RemyBrain.class.getDeclaredMethod("toPlan", Intent.class, String.class, String.class, String.class, String.class);
        toPlan.setAccessible(true);
        RemyBrain.Plan p = (RemyBrain.Plan) toPlan.invoke(null, Intent.GATHER, "chop 8 wood", "xxBP00", "minecraft:oak_log", "rules");
        eq(p.commands(), List.of("get oak_log 8"), "gather plan");
        p = (RemyBrain.Plan) toPlan.invoke(null, Intent.GATHER, "get 4 andesite alloy", "xxBP00", "create:andesite_alloy", "rules");
        eq(p.commands(), List.of("get create:andesite_alloy 4"), "modded keeps namespace");
        p = (RemyBrain.Plan) toPlan.invoke(null, Intent.DEFEND, "protect me", "xxBP00", null, "rules");
        eq(p.commands(), List.of("set_follow_mode DEFENDER", "follow xxBP00"), "defend plan");
        p = (RemyBrain.Plan) toPlan.invoke(null, Intent.GIVE, "give me 5 bread", "xxBP00", "minecraft:bread", "rules");
        eq(p.commands(), List.of("give xxBP00 bread 5"), "give plan");
        p = (RemyBrain.Plan) toPlan.invoke(null, Intent.STOP, "stop", "xxBP00", null, "rules");
        eq(p.control(), "stop", "stop is a control, not a model task");
        p = (RemyBrain.Plan) toPlan.invoke(null, Intent.ATTACK, "attack 3 zombies", "xxBP00", "minecraft:zombie", "rules");
        eq(p.commands(), List.of("attack zombie 3"), "attack plan");

        // Decision request + response parsing (Jev/OpenRouter and tev1/Ollama share the schema)
        Method req = RemyBrain.class.getDeclaredMethod("decisionRequest", String.class, String.class, List.class);
        req.setAccessible(true);
        JsonObject body = (JsonObject) req.invoke(null, "we need dinner", "owner at 1 2 3", List.of("minecraft:bread", "minecraft:cooked_beef"));
        eq(body.getAsJsonObject("questions").getAsJsonObject("intent").get("type").getAsString(), "choice", "intent is a choice");
        eq(body.getAsJsonObject("questions").getAsJsonObject("intent").getAsJsonObject("criteria").size(), Intent.values().length, "all intents offered");
        eq(body.getAsJsonObject("questions").getAsJsonObject("target").getAsJsonObject("criteria").has("minecraft:bread"), true, "target options");
        Method choice = RemyBrain.class.getDeclaredMethod("choice", JsonObject.class, String.class);
        choice.setAccessible(true);
        JsonObject jev = JsonParser.parseString("{\"answers\":{\"intent\":{\"type\":\"choice\",\"choice\":\"get_food\",\"confidence\":0.8}}}").getAsJsonObject();
        eq(choice.invoke(null, jev, "intent"), "get_food", "parse Jev answer");
        JsonObject flat = JsonParser.parseString("{\"choices\":{\"intent\":\"gather\"}}").getAsJsonObject();
        eq(choice.invoke(null, flat, "intent"), "gather", "parse flat answer");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

package com.brandopakel.remy.playerengine.architect;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;

import java.nio.file.Files;
import java.nio.file.Path;

/** Checks Blueprint parsing/rotation/materials against the real vanilla registry. */
public class BlueprintCheck {
    static int failures = 0;

    static void ok(boolean cond, String what) {
        System.out.println((cond ? "ok   " : "FAIL ") + what);
        if (!cond) failures++;
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        String json = Files.readString(Path.of(args.length > 0 ? args[0] : "designs/cottage.json"));
        Blueprint.Parsed p = Blueprint.parse(json, Architect.REGISTRY_RESOLVER, 24, 20, 24);
        ok(p.ok(), "cottage parses: " + p.errors() + " warnings " + p.warnings());
        Blueprint bp = p.blueprint();
        ok(bp.sx == 9 && bp.sy == 10 && bp.sz == 11, "size 9x10x11 got " + bp.sx + "x" + bp.sy + "x" + bp.sz);
        BlockState door = bp.get(4, 1, 9);
        ok(door.isOf(Blocks.SPRUCE_DOOR), "door at front");
        ok(bp.get(4, 2, 9).isOf(Blocks.SPRUCE_DOOR) && bp.get(4, 2, 9).get(Properties.DOUBLE_BLOCK_HALF).asString().equals("upper"), "upper door half added");
        ok(bp.get(2, 1, 2).isOf(Blocks.RED_BED), "bed head added north of foot");
        ok(bp.materials().get(Blocks.SPRUCE_DOOR.asItem()) == 1, "door counted once");
        ok(bp.materials().get(Blocks.RED_BED.asItem()) == 1, "bed counted once");
        ok(bp.get(4, 0, 0) == null, "spaces are 'leave'");
        ok(bp.get(4, 2, 4) != null && bp.get(4, 2, 4).isAir(), "interior air");

        // Rotation: front (south) should face west after CLOCKWISE_90; stairs rotate too.
        Blueprint r = bp.rotate(Blueprint.rotationToFace(Direction.WEST));
        ok(r.sx == 11 && r.sz == 9, "rotated footprint 11x9");
        // The door was on the south edge (z=8 of 9..), now must be on the west edge (x=0..1).
        boolean doorWest = false;
        for (int z = 0; z < r.sz; z++) for (int x = 0; x < 3; x++) if (r.get(x, 1, z) != null && r.get(x, 1, z).isOf(Blocks.SPRUCE_DOOR)) doorWest = true;
        ok(doorWest, "door moved to the west side");
        BlockState stair = bp.get(0, 4, 0); // north eave, facing south
        BlockState rotated = stair.rotate(BlockRotation.CLOCKWISE_90);
        ok(rotated.get(Properties.HORIZONTAL_FACING) == Direction.WEST, "stair facing rotates south->west");

        // Validation errors are reported, not thrown.
        Blueprint.Parsed bad = Blueprint.parse("{\"palette\":{\"A\":\"minecraft:not_a_block\"},\"layers\":[[\"AB\"]]}",
                Architect.REGISTRY_RESOLVER, 24, 20, 24);
        ok(!bad.ok() && bad.errors().size() >= 1, "bad design rejected: " + bad.errors());
        Blueprint.Parsed big = Blueprint.parse("{\"palette\":{\"A\":\"minecraft:stone\"},\"layers\":[[\"" + "A".repeat(30) + "\"]]}",
                Architect.REGISTRY_RESOLVER, 24, 20, 24);
        ok(!big.ok(), "oversized design rejected");
        Blueprint.Parsed fenced = Blueprint.parse("```json\n{\"palette\":{\"A\":\"stone\"},\"layers\":[[\"A\"]]}\n```",
                Architect.REGISTRY_RESOLVER, 24, 20, 24);
        ok(fenced.ok(), "code fences stripped, bare ids accepted");
        ok(BlockCatalog.family("oak_stairs").equals("oak") && BlockCatalog.family("oak_planks").equals("oak")
                && BlockCatalog.family("stone_bricks").equals("stone_brick") && BlockCatalog.family("stone_brick_wall").equals("stone_brick"), "families group variants");
        ok(BlockCatalog.describeColor("#6b5333").contains("brown"), "colour words: " + BlockCatalog.describeColor("#6b5333"));
        System.out.println(bp.describe());
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

package kryptos.content;

import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.blocks.environment.OreBlock;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitPlan;

import static mindustry.type.ItemStack.with;

public class KryptosBlocks {
    public static OreBlock oreCustom;
    public static UnitFactory factory;
    public static GenericCrafter oreFactory;

    public static void load() {
        oreCustom = new OreBlock("ore-kryptos", KryptosItems.customOre) {{
            // oreDefault left false (default) on purpose: this ore must NOT
            // compete with Copper/Lead/Titanium/etc. in automatic map
            // generation, or it crowds them out of every eligible tile.
            // It can still be placed by hand in the map editor.
            oreThreshold = 0.82f;
            oreScale = 24f;
            variants = 3;
        }};

        // Sprite: sprites/blocks/production/kryptos-factory.png (3x3, matches
        // size below). Placement cost uses ordinary vanilla items -- only the
        // unit's own production cost is Kryptos ore, per design. Requires
        // KryptosUnits.load() to have already run (see KryptosMod.loadContent
        // ordering: Items -> Units -> Blocks).
        factory = new UnitFactory("kryptos-factory") {{
            alwaysUnlocked = true;
            requirements(Category.units, with(Items.copper, 1));
            size = 3;
            consumePower(1.5f);

            plans.add(new UnitPlan(KryptosUnits.strider, 60f * 5f, with(Items.copper, 1)));
        }};

        // KryptosFactory: the actual ore-processing building (distinct from
        // the UnitFactory above, which is unfortunately also named
        // "kryptos-factory" internally -- do not confuse the two).
        // Sprite needed: sprites/blocks/production/kryptos-ore-factory.png
        // (2x2, plus an optional "-rotator"/top layer if drawer needs one).
        oreFactory = new GenericCrafter("kryptos-ore-factory") {{
            requirements(Category.crafting, with(Items.silicon, 50, Items.titanium, 40, Items.lead, 40));
            size = 2;
            craftTime = 50f;
            craftEffect = Fx.pulverizeMedium;
            outputItem = new ItemStack(KryptosItems.alloy, 1);

            consumeItem(KryptosItems.customOre, 2);
        }};
    }
}

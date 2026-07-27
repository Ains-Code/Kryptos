package kryptos.content;

import mindustry.content.Items;
import mindustry.type.Category;
import mindustry.world.blocks.environment.OreBlock;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitPlan;

import static mindustry.type.ItemStack.with;

public class KryptosBlocks {
    public static OreBlock oreCustom;
    public static UnitFactory factory;

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
            requirements(Category.units, with(Items.silicon, 80, Items.titanium, 60, Items.lead, 60));
            size = 3;
            consumePower(1.5f);

            plans.add(new UnitPlan(KryptosUnits.strider, 60f * 25f, with(KryptosItems.customOre, 40)));
        }};
    }
}

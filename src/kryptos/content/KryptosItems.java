package kryptos.content;

import arc.graphics.Color;
import mindustry.type.Item;

public class KryptosItems {
    public static Item customOre;
    public static Item alloy;

    public static void load() {
        customOre = new Item("kryptos-ore", Color.valueOf("7b8494")) {{
            hardness = 1;
            cost = 1.1f;
            charge = 0f;
            explosiveness = 0f;
            radioactivity = 0f;
            flammability = 0f;
        }};

        // Refined output of KryptosBlocks.oreFactory. Glacial Precursor palette:
        // pale cyan-white to read as a "processed" tier above the raw grey ore.
        alloy = new Item("kryptos-alloy", Color.valueOf("bfe3e0")) {{
            hardness = 3;
            cost = 1.4f;
            charge = 0f;
            explosiveness = 0f;
            radioactivity = 0f;
            flammability = 0f;
        }};
    }
}

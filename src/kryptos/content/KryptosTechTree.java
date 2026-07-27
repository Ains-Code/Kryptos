package kryptos.content;

import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.TechTree.TechNode;
import mindustry.type.ItemStack;

import static mindustry.type.ItemStack.with;

/**
 * Grafts Kryptos content onto Serpulo's existing tech tree.
 *
 * Without this, nothing here has a TechNode at all, which means it only
 * ever appeared "unlocked" in sandbox/custom games (where unlock checks
 * are skipped entirely). In an actual campaign save, unlocked content is
 * driven by the Database/research tree -- so anything missing a TechNode
 * is invisible in both the Database screen and the real build menu, even
 * though it loads and runs fine.
 *
 * IMPORTANT: this deliberately does NOT call TechTree.node(existingVanillaBlock, ...)
 * for the parent -- that helper *creates a new node* for whatever content
 * you pass it, which would silently duplicate/overwrite Blocks.siliconSmelter's
 * real vanilla TechNode. Instead we read the existing node off
 * Blocks.siliconSmelter.techNode and attach directly to it via the public
 * TechNode(parent, content, requirements) constructor, which only registers
 * *new* nodes for our own content and leaves the vanilla tree untouched.
 *
 * Chain: silicon smelter (vanilla, early-game) -> ore factory (refines
 * Kryptos ore into alloy) -> unit factory (spends alloy... er, ore, to
 * build Striders). Research the refinery before the unit factory, since
 * the unit factory's plan consumes the raw ore the refinery also wants.
 */
public final class KryptosTechTree {

    private KryptosTechTree() {
        // Utility class
    }

    public static void load() {
        TechNode siliconSmelterNode = Blocks.siliconSmelter.techNode;

        if (siliconSmelterNode == null) {
            // Defensive: if vanilla ever changes and this node stops existing,
            // don't NPE and take the rest of mod init down with it.
            return;
        }

        ItemStack[] oreFactoryCost = with(Items.silicon, 60, Items.titanium, 45);
        TechNode oreFactoryNode = new TechNode(siliconSmelterNode, KryptosBlocks.oreFactory, oreFactoryCost);

        ItemStack[] factoryCost = with(Items.silicon, 90, Items.titanium, 70, Items.lead, 60);
        new TechNode(oreFactoryNode, KryptosBlocks.factory, factoryCost);
    }
}

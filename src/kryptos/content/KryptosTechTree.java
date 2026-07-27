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
 * you pass it, which would silently duplicate/overwrite Blocks.coreShard's
 * real vanilla TechNode (the root of the entire Serpulo tree). Instead we
 * read the existing node off Blocks.coreShard.techNode and attach directly
 * to it via the public TechNode(parent, content, requirements) constructor,
 * which only registers *new* nodes for our own content and leaves the
 * vanilla tree untouched.
 *
 * Chain: Core: Foundation (vanilla root, always researched) -> ore factory
 * (refines Kryptos ore into alloy) -> unit factory (spends ore to build
 * Striders). Hanging it straight off the core means it shows up immediately
 * as its own branch instead of being buried behind an unrelated vanilla
 * prerequisite.
 */
public final class KryptosTechTree {

    private KryptosTechTree() {
        // Utility class
    }

    public static void load() {
        TechNode coreNode = Blocks.coreShard.techNode;

        if (coreNode == null) {
            // Defensive: if vanilla ever changes and this node stops existing,
            // don't NPE and take the rest of mod init down with it.
            return;
        }

        ItemStack[] oreFactoryCost = with(Items.silicon, 60, Items.titanium, 45);
        TechNode oreFactoryNode = new TechNode(coreNode, KryptosBlocks.oreFactory, oreFactoryCost);

        ItemStack[] factoryCost = with(Items.silicon, 90, Items.titanium, 70, Items.lead, 60);
        new TechNode(oreFactoryNode, KryptosBlocks.factory, factoryCost);
    }
}

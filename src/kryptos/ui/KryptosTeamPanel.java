package kryptos.ui;

import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * "Team Resources" panel — toggled from {@link KryptosHud}. Shows the
 * player's core mineral counts plus a quick unit-count comparison against
 * the enemy, refreshed live every frame while visible.
 */
public class KryptosTeamPanel {
    private static final Color MINERAL_COLOR = Color.valueOf("8ff5ff"); // energy accent (hot)
    private static final Color OWN_COLOR = Color.valueOf("4ce0ff");     // energy accent
    private static final Color ENEMY_COLOR = Color.valueOf("ff5a4c");   // warning accent
    private static final Color LABEL_COLOR = Color.valueOf("7b8494");   // structural steel

    private static Table table;

    public static Table build() {
        table = new Table(Styles.black6);
        table.visible = false;
        table.margin(8f);
        table.update(KryptosTeamPanel::refresh);
        return table;
    }

    public static void setShown(boolean shown) {
        if (table != null) table.visible = shown;
    }

    private static void refresh() {
        if (table == null || !table.visible) return;
        table.clearChildren();

        Team own = Vars.player.team();

        // --- Units: yours vs enemy (manual count; Groups.unit has no count(predicate)) ---
        int ownUnits = 0;
        int enemyUnits = 0;
        for (Unit u : Groups.unit) {
            if (u.team == own) ownUnits++;
            else if (u.team.active()) enemyUnits++;
        }

        table.add("Units").color(LABEL_COLOR).left().padRight(6f);
        Label mine = new Label(String.valueOf(ownUnits));
        mine.setColor(OWN_COLOR);
        table.add(mine).left();
        table.add(" / ").color(LABEL_COLOR);
        Label theirs = new Label(String.valueOf(enemyUnits));
        theirs.setColor(ENEMY_COLOR);
        table.add(theirs).left().row();

        // --- Minerals: core item storage ---
        CoreBuild core = own.core();
        if (core != null) {
            Table minerals = new Table();
            boolean any = false;
            for (Item item : Vars.content.items()) {
                int amount = core.items.get(item);
                if (amount <= 0) continue;
                any = true;
                minerals.image(item.uiIcon).size(20f).padRight(4f);
                Label amt = new Label(String.valueOf(amount));
                amt.setColor(MINERAL_COLOR);
                minerals.add(amt).padRight(10f);
            }
            if (any) {
                table.add(minerals).colspan(4).left().padTop(4f);
            }
        }

        table.pack();
    }
}

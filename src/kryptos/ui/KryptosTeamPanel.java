package kryptos.ui;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.ui.Styles;

import static mindustry.Vars.ui;

/**
 * "Team Resources" — toggled from {@link KryptosHud}. Two separate,
 * independently draggable panels (each remembers its own position):
 *  - units panel: "Unit:" / "Enemies:" live counts
 *  - minerals panel: core item storage, icon + amount per item
 */
public class KryptosTeamPanel {
    private static final Color OWN_COLOR = Color.valueOf("4ce0ff");
    private static final Color ENEMY_COLOR = Color.valueOf("ff5a4c");
    private static final Color MINERAL_COLOR = Color.valueOf("8ff5ff");
    private static final Color LABEL_COLOR = Color.valueOf("7b8494");

    private static final float HANDLE_HEIGHT = 10f;
    private static final float DRAG_THRESHOLD = 6f;
    private static final int MINERALS_PER_ROW = 4;

    private static Table unitsContent, unitsContainer;
    private static Table mineralsContent, mineralsContainer;

    public static void build() {
        unitsContent = new Table(Styles.black6);
        unitsContent.margin(8f);
        unitsContent.update(KryptosTeamPanel::refreshUnits);
        unitsContainer = wrapDraggable(unitsContent, "kryptos-units-x", "kryptos-units-y", 16f, 160f);

        mineralsContent = new Table(Styles.black6);
        mineralsContent.margin(8f);
        mineralsContent.update(KryptosTeamPanel::refreshMinerals);
        mineralsContainer = wrapDraggable(mineralsContent, "kryptos-minerals-x", "kryptos-minerals-y", 16f, 220f);

        setShown(false);

        ui.hudGroup.addChild(unitsContainer);
        ui.hudGroup.addChild(mineralsContainer);
    }

    public static void setShown(boolean shown) {
        if (unitsContainer != null) unitsContainer.visible = shown;
        if (mineralsContainer != null) mineralsContainer.visible = shown;
    }

    /** Wraps content in a drag-handle strip + drag behavior + saved position, same pattern as {@link KryptosHud}. */
    private static Table wrapDraggable(Table content, String settingX, String settingY, float defaultX, float defaultY) {
        Table handle = new Table(t -> t.background(Styles.black6));
        handle.touchable = Touchable.enabled;

        Table container = new Table();
        container.touchable = Touchable.enabled;
        container.top();
        container.add(handle).growX().height(HANDLE_HEIGHT).row();
        container.add(content);
        container.pack();

        attachDrag(container, settingX, settingY);

        float x = Core.settings.getFloat(settingX, defaultX);
        float y = Core.settings.getFloat(settingY, defaultY);
        container.setPosition(x, y);

        return container;
    }

    private static void attachDrag(Table container, String settingX, String settingY) {
        final float[] dragOrigin = new float[2];
        final float[] elemOrigin = new float[2];
        final boolean[] dragging = {false};

        container.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                dragOrigin[0] = event.stageX;
                dragOrigin[1] = event.stageY;
                elemOrigin[0] = container.x;
                elemOrigin[1] = container.y;
                dragging[0] = false;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                float dx = event.stageX - dragOrigin[0];
                float dy = event.stageY - dragOrigin[1];
                if (!dragging[0] && Mathf.dst(dx, dy) < DRAG_THRESHOLD) return;
                dragging[0] = true;

                float nx = Mathf.clamp(elemOrigin[0] + dx, 0f, Core.graphics.getWidth() - container.getWidth());
                float ny = Mathf.clamp(elemOrigin[1] + dy, 0f, Core.graphics.getHeight() - container.getHeight());
                container.setPosition(nx, ny);
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (dragging[0]) {
                    Core.settings.put(settingX, container.x);
                    Core.settings.put(settingY, container.y);
                }
                dragging[0] = false;
            }
        });
    }

    private static void refreshUnits() {
        if (unitsContent == null || !unitsContainer.visible) return;
        unitsContent.clearChildren();

        Team own = Vars.player.team();
        int ownUnits = 0;
        int enemyUnits = 0;
        for (Unit u : Groups.unit) {
            if (u.team == own) ownUnits++;
            else if (u.team.active()) enemyUnits++;
        }

        unitsContent.add("Unit:").color(LABEL_COLOR).left().padRight(6f);
        Label mine = new Label(String.valueOf(ownUnits));
        mine.setColor(OWN_COLOR);
        unitsContent.add(mine).left().row();

        unitsContent.add("Enemies:").color(LABEL_COLOR).left().padRight(6f);
        Label theirs = new Label(String.valueOf(enemyUnits));
        theirs.setColor(ENEMY_COLOR);
        unitsContent.add(theirs).left();

        unitsContent.pack();
        unitsContainer.pack();
    }

    private static void refreshMinerals() {
        if (mineralsContent == null || !mineralsContainer.visible) return;
        mineralsContent.clearChildren();

        Team own = Vars.player.team();
        Building core = own.core();
        if (core == null) return;

        int col = 0;
        for (Item item : Vars.content.items()) {
            int amount = core.items.get(item);
            if (amount <= 0) continue;

            mineralsContent.image(item.uiIcon).size(20f).padRight(4f);
            Label amt = new Label(String.valueOf(amount));
            amt.setColor(MINERAL_COLOR);
            mineralsContent.add(amt).padRight(10f);

            col++;
            if (col % MINERALS_PER_ROW == 0) mineralsContent.row();
        }

        mineralsContent.pack();
        mineralsContainer.pack();
    }
}

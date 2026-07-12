package kryptos.ui;

import arc.Core;
import arc.func.Boolp;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.Mathf;
import arc.scene.actions.Actions;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import mindustry.ui.Styles;

import static mindustry.Vars.ui;

public class KryptosHud {
    private static final float ICON_SIZE = 52f;
    private static final float BTN_SIZE = 40f;
    private static final float HANDLE_HEIGHT = 14f;
    private static final float DRAG_THRESHOLD = 6f;

    private static final String SETTING_X = "kryptos-hud-x";
    private static final String SETTING_Y = "kryptos-hud-y";
    private static final float DEFAULT_X = 16f;
    private static final float DEFAULT_Y = 16f;

    public static boolean autoplay = false;
    public static boolean healthBars = false;
    public static boolean pathfinding = false;
    public static boolean rangeDisplay = false;
    public static boolean teamResources = false;

    private static Table container;
    private static Table panel;
    private static boolean open = false;

    public static void build() {
        container = new Table();
        container.touchable = Touchable.enabled;

        Table handle = buildDragHandle();

        panel = new Table(Styles.black6);
        panel.visible = false;
        panel.pack();

        addToggle(panel, "kryptos-icon-autoplay", "Autoplay", () -> autoplay, b -> autoplay = b);
        addToggle(panel, "kryptos-icon-health", "Health Bars", () -> healthBars, b -> healthBars = b);
        addToggle(panel, "kryptos-icon-path", "Pathfinding", () -> pathfinding, b -> pathfinding = b);
        addToggle(panel, "kryptos-icon-range", "Range Display", () -> rangeDisplay, b -> rangeDisplay = b);
        addToggle(panel, "kryptos-icon-team", "Team Resources", () -> teamResources, b -> teamResources = b);

        ImageButton icon = new ImageButton(Core.atlas.drawable("kryptos-icon"), Styles.emptyi);
        icon.resizeImage(ICON_SIZE);
        icon.clicked(KryptosHud::toggle);
        icon.addListener(new Tooltip(t -> t.background(Styles.black6).add("Kryptos HUD").pad(4f)));

        Table row = new Table();
        row.add(panel).padRight(8f);
        row.add(icon).size(ICON_SIZE + 12f);

        container.top();
        container.add(handle).growX().height(HANDLE_HEIGHT).row();
        container.add(row);
        container.pack();

        attachContainerDrag();

        loadPosition();

        ui.hudGroup.addChild(container);
    }

    /** Purely visual grip strip now — actual dragging is handled by {@link #attachContainerDrag()}. */
    private static Table buildDragHandle() {
        Table handle = new Table(t -> t.background(Styles.black6));
        handle.touchable = Touchable.enabled;
        return handle;
    }

    /**
     * Lets the player drag the widget from anywhere on it (background, gaps,
     * the handle strip) instead of only a thin strip. Buttons are excluded so
     * taps on the main icon and toggle buttons keep working normally: if a
     * touch starts on a {@link Button}, we return false immediately and let
     * the button handle its own click, never starting a drag from it.
     */
    private static void attachContainerDrag() {
        final float[] dragOrigin = new float[2]; // touch-down position in stage coords
        final float[] elemOrigin = new float[2]; // container position at touch-down
        final boolean[] dragging = {false};

        container.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (event.targetActor instanceof Button) {
                    return false;
                }

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
                if (dragging[0]) savePosition();
                dragging[0] = false;
            }
        });
    }

    private static void loadPosition() {
        float x = Core.settings.getFloat(SETTING_X, DEFAULT_X);
        float y = Core.settings.getFloat(SETTING_Y, DEFAULT_Y);
        container.setPosition(x, y);
    }

    private static void savePosition() {
        Core.settings.put(SETTING_X, container.x);
        Core.settings.put(SETTING_Y, container.y);
    }

    private static void addToggle(Table into, String iconName, String tooltipText, Boolp getter, arc.func.Boolc setter) {
        ImageButton btn = new ImageButton(Core.atlas.drawable(iconName), Styles.emptyi);
        btn.resizeImage(BTN_SIZE * 0.5f);
        btn.update(() -> btn.setChecked(getter.get()));
        btn.clicked(() -> setter.get(!getter.get()));
        btn.addListener(new Tooltip(t -> t.background(Styles.black6).add(tooltipText).pad(4f)));
        into.add(btn).size(BTN_SIZE).pad(4f);
    }

    public static void toggle() {
        if (open) close(); else open();
    }

    public static void open() {
        if (open) return;
        open = true;
        panel.visible = true;
        panel.clearActions();
        panel.actions(Actions.alpha(0f), Actions.parallel(
            Actions.alpha(1f, 0.32f, Interp.pow3Out)
        ));
    }

    public static void close() {
        if (!open) return;
        open = false;
        panel.clearActions();
        panel.actions(Actions.alpha(0f, 0.24f, Interp.pow3In), Actions.visible(false));
    }
}

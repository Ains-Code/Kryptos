package kryptos.ui;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import kryptos.automation.KryptosAutoConveyor;
import mindustry.content.Blocks;
import mindustry.ui.Styles;

import static mindustry.Vars.ui;

/**
 * The "many different automations" panel requested alongside Autoplay --
 * only visible while {@link KryptosHud#autoplay} is on (see the wiring in
 * {@link KryptosHud#build()}). Each automation gets one toggle row here;
 * this module ships with a single one, Auto Conveyor
 * ({@link KryptosAutoConveyor}), and is built so more can be added the same
 * way later (one more {@code KryptosHud.addToggle} call).
 *
 * Follows the same draggable-panel-with-remembered-position pattern as
 * {@link KryptosTeamPanel}.
 */
public class KryptosAutomationPanel {
    private static final Color LABEL_COLOR = Color.valueOf("7b8494");
    private static final Color STATUS_COLOR = Color.valueOf("8ff5ff");

    private static final String SETTING_X = "kryptos-automation-x";
    private static final String SETTING_Y = "kryptos-automation-y";
    private static final float DEFAULT_X = 16f;
    private static final float DEFAULT_Y = 280f;
    private static final float HANDLE_HEIGHT = 10f;
    private static final float DRAG_THRESHOLD = 6f;

    /** Master switch for the auto-conveyor module; read by {@link KryptosAutoConveyor}. */
    public static boolean autoConveyor = false;

    private static Table container;
    private static Table content;
    private static Label statusLabel;

    public static void build() {
        content = new Table(Styles.black6);
        content.margin(8f);

        content.add("Automation").color(LABEL_COLOR).left().padBottom(6f).row();

        KryptosHud.addToggle(content, new TextureRegionDrawable(Blocks.conveyor.uiIcon),
                "Auto Conveyor", () -> autoConveyor, b -> {
                    autoConveyor = b;
                    if (b) KryptosAutoConveyor.requestImmediateScan();
                });
        content.row();

        statusLabel = new Label("");
        statusLabel.setColor(STATUS_COLOR);
        statusLabel.update(() -> statusLabel.setText("Served: " + KryptosAutoConveyor.servedCount()));
        content.add(statusLabel).left().padTop(4f);

        container = wrapDraggable(content, SETTING_X, SETTING_Y, DEFAULT_X, DEFAULT_Y);
        setShown(false);

        ui.hudGroup.addChild(container);
    }

    public static void setShown(boolean shown) {
        if (container != null) container.visible = shown;
    }

    /** Wraps content in a drag-handle strip + drag behavior + saved position, same pattern as {@link KryptosTeamPanel}. */
    private static Table wrapDraggable(Table inner, String settingX, String settingY, float defaultX, float defaultY) {
        Table handle = new Table(t -> t.background(Styles.black6));
        handle.touchable = Touchable.enabled;

        Table wrapper = new Table();
        wrapper.touchable = Touchable.enabled;
        wrapper.top();
        wrapper.add(handle).growX().height(HANDLE_HEIGHT).row();
        wrapper.add(inner);
        wrapper.pack();

        attachDrag(wrapper, settingX, settingY);

        float x = Core.settings.getFloat(settingX, defaultX);
        float y = Core.settings.getFloat(settingY, defaultY);
        wrapper.setPosition(x, y);

        return wrapper;
    }

    private static void attachDrag(Table wrapper, String settingX, String settingY) {
        final float[] dragOrigin = new float[2];
        final float[] elemOrigin = new float[2];
        final boolean[] dragging = {false};

        wrapper.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                dragOrigin[0] = event.stageX;
                dragOrigin[1] = event.stageY;
                elemOrigin[0] = wrapper.x;
                elemOrigin[1] = wrapper.y;
                dragging[0] = false;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                float dx = event.stageX - dragOrigin[0];
                float dy = event.stageY - dragOrigin[1];
                if (!dragging[0] && Mathf.dst(dx, dy) < DRAG_THRESHOLD) return;
                dragging[0] = true;

                float nx = Mathf.clamp(elemOrigin[0] + dx, 0f, Core.graphics.getWidth() - wrapper.getWidth());
                float ny = Mathf.clamp(elemOrigin[1] + dy, 0f, Core.graphics.getHeight() - wrapper.getHeight());
                wrapper.setPosition(nx, ny);
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (dragging[0]) {
                    Core.settings.put(settingX, wrapper.x);
                    Core.settings.put(settingY, wrapper.y);
                }
                dragging[0] = false;
            }
        });
    }
}


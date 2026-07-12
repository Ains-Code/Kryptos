package kryptos.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.ResizeEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;

import static mindustry.Vars.ui;

/**
 * Draggable panel with speed presets. The active multiplier eases toward the
 * selected speed each frame instead of snapping, so switching speeds never
 * jolts the simulation.
 */
public class KryptosTimeControl extends Table {
    private static final float[] SPEEDS = { 0.25f, 0.5f, 1f, 2f, 4f };
    private static final float SMOOTHING = 0.08f;
    private static final String SETTING_X = "kryptos-time-x";
    private static final String SETTING_Y = "kryptos-time-y";
    private static final float DEFAULT_X = 16f;
    private static final float DEFAULT_Y = 80f;

    private static KryptosTimeControl instance;

    private float target = 1f;
    private float current = 1f;

    public static void init() {
        instance = new KryptosTimeControl();
        instance.touchable = Touchable.childrenOnly;
        instance.visible(() -> ui.hudfrag.shown && KryptosHud.timeControl);

        instance.setPosition(
                Core.settings.getFloat(SETTING_X, DEFAULT_X),
                Core.settings.getFloat(SETTING_Y, DEFAULT_Y));

        Events.on(ResizeEvent.class, e -> instance.rebuild());
        Events.on(WorldLoadEvent.class, e -> instance.resetSpeed());
        Events.run(Trigger.update, instance::smoothUpdate);

        Core.app.post(instance::rebuild);
        ui.hudGroup.addChild(instance);
    }

    /** Restores normal speed on new games/maps so a fast load doesn't carry over. */
    private void resetSpeed() {
        target = 1f;
        current = 1f;
        Time.setDeltaProvider(() -> Core.graphics.getDeltaTime() * 60f);
    }

    private void smoothUpdate() {
        if (!KryptosHud.timeControl) {
            return;
        }
        current = Mathf.lerpDelta(current, target, SMOOTHING);
        if (Math.abs(current - target) < 0.001f) {
            current = target;
        }
        Time.setDeltaProvider(() -> Core.graphics.getDeltaTime() * 60f * current);
    }

    void rebuild() {
        clear();

        Table container = new Table();
        container.background(Styles.black6);
        container.touchable = Touchable.enabled;

        float buttonSize = 40f;
        float margin = 5f;

        container.button(Icon.move, Styles.clearNonei, () -> {
        })
                .size(buttonSize)
                .margin(margin)
                .get()
                .addListener(new InputListener() {
                    float lastX, lastY;

                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        lastX = x;
                        lastY = y;
                        return true;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer) {
                        moveBy(x - lastX, y - lastY);

                        float sw = Core.graphics.getWidth();
                        float sh = Core.graphics.getHeight();
                        float nx = Mathf.clamp(KryptosTimeControl.this.x, 0, sw - getWidth());
                        float ny = Mathf.clamp(KryptosTimeControl.this.y, 0, sh - getHeight());
                        setPosition(nx, ny);

                        Core.settings.put(SETTING_X, nx);
                        Core.settings.put(SETTING_Y, ny);
                    }
                });

        Image sep = new Image(Tex.whiteui);
        sep.setColor(Pal.accent);
        container.add(sep).width(2f).fillY();

        Table content = new Table();
        for (float speed : SPEEDS) {
            Color color = speed == target ? Pal.accent : Pal.gray;
            String label = "[#" + color + "]" + (speed < 1f ? speed : (int) speed) + "x";

            content.button(label, Styles.cleart, () -> {
                target = speed;
                rebuild();
            })
                    .wrapLabel(false)
                    .height(buttonSize)
                    .width(buttonSize * 1.3f)
                    .margin(margin);
        }
        container.add(content);

        add(container);
        pack();
    }
}

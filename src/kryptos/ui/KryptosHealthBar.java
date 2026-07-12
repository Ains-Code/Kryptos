package kryptos.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Rect;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.OreBlock;

import static mindustry.Vars.player;
import static mindustry.Vars.renderer;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * Draws HP (and shield) bars above damaged units and buildings, and marks
 * visible ore tiles with a small item icon under the tile so resources are
 * readable without opening the map overlay.
 */
public class KryptosHealthBar {
    private static final float BAR_SCALE = 1f;
    private static final float ORE_ZOOM_THRESHOLD = 2.6f;
    private static final float ORE_ICON_SIZE = 6f;

    private static TextureRegion barRegion;
    private static final Rect viewBounds = new Rect();

    public static void init() {
        Events.run(Trigger.draw, KryptosHealthBar::draw);
    }

    private static void draw() {
        if (!KryptosHud.healthBars || !state.isGame() || Vars.ui.hudfrag == null || !Vars.ui.hudfrag.shown) {
            return;
        }

        if (barRegion == null) {
            barRegion = Core.atlas.white();
        }

        Core.camera.bounds(viewBounds);
        float cx = Core.camera.position.x;
        float cy = Core.camera.position.y;
        float cw = Core.camera.width;
        float ch = Core.camera.height;

        Draw.z(Layer.shields + 5f);

        Groups.unit.intersect(cx - cw / 2f, cy - ch / 2f, cw, ch, KryptosHealthBar::drawUnit);

        Vars.indexer.eachBlock(null, cx, cy, Math.max(cw, ch), b -> true, KryptosHealthBar::drawBuilding);

        drawOreLabels();

        Draw.reset();
    }

    private static void drawUnit(Unit unit) {
        if (!unit.isValid()) {
            return;
        }
        boolean damaged = unit.health < unit.maxHealth;
        boolean shielded = unit.shield > 0;
        if (!damaged && !shielded) {
            return;
        }

        float x = unit.x;
        float y = unit.y + (unit.hitSize * 0.8f + 3f) * BAR_SCALE;
        float w = unit.hitSize * 2.5f;
        drawBar(x, y, w, unit.health, unit.maxHealth, unit.team.color);

        if (unit.shield > 0) {
            drawShieldPips(x, y, w, unit.shield, Math.max(unit.maxHealth, 1f), unit.team.color);
        }
    }

    private static void drawBuilding(Building build) {
        if (!build.isValid()) {
            return;
        }
        if (build.health >= build.maxHealth) {
            return;
        }

        float x = build.x;
        float y = build.y + build.block.size * Vars.tilesize * 0.5f + 3f;
        float w = build.block.size * Vars.tilesize * 0.9f;
        drawBar(x, y, w, build.health, build.maxHealth, build.team.color);
    }

    private static void drawBar(float x, float y, float w, float health, float maxHealth, Color teamColor) {
        float h = 2f * BAR_SCALE;
        if (Float.isNaN(maxHealth) || maxHealth <= 0f) {
            maxHealth = 1f;
        }
        float pct = Math.max(0f, Math.min(1f, health / maxHealth));

        Draw.color(Color.black, 0.6f);
        Draw.rect(barRegion, x, y, w + 2f, h + 2f);

        if (pct > 0) {
            float left = x - w / 2f;
            float filledW = w * pct;
            Draw.color(teamColor, 0.85f);
            Draw.rect(barRegion, left + filledW / 2f, y, filledW, h);
        }
        Draw.reset();
    }

    private static void drawShieldPips(float x, float startY, float w, float shield, float maxHealth,
            Color teamColor) {
        float h = 2f * BAR_SCALE;
        float shieldValue = Math.min(shield / maxHealth, 20f);
        float y = startY;

        while (shieldValue > 0) {
            y += h * 1.8f;
            Draw.color(Color.black, 0.6f);
            Draw.rect(barRegion, x, y, w + 2f, h + 2f);

            float pct = Math.min(shieldValue, 1f);
            float left = x - w / 2f;
            float filledW = w * pct;
            Draw.color(Pal.shield, 0.6f);
            Draw.rect(barRegion, left + filledW / 2f, y, filledW, h);

            shieldValue -= 1;
        }
        Draw.reset();
    }

    /** Marks ore tiles in view with a small icon under the tile ("sa baba"). */
    private static void drawOreLabels() {
        float zoom = renderer.getScale();
        if (zoom < ORE_ZOOM_THRESHOLD) {
            return;
        }

        int x0 = Math.max(0, (int) (viewBounds.x / Vars.tilesize) - 1);
        int y0 = Math.max(0, (int) (viewBounds.y / Vars.tilesize) - 1);
        int x1 = Math.min(world.width() - 1, (int) ((viewBounds.x + viewBounds.width) / Vars.tilesize) + 1);
        int y1 = Math.min(world.height() - 1, (int) ((viewBounds.y + viewBounds.height) / Vars.tilesize) + 1);

        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                Tile tile = world.tile(tx, ty);
                if (tile == null || !(tile.overlay() instanceof OreBlock ore)) {
                    continue;
                }
                Item item = ore.itemDrop;
                if (item == null) {
                    continue;
                }

                TextureRegion icon = item.uiIcon;
                if (icon == null || !icon.found()) {
                    continue;
                }

                float ix = tile.worldx();
                float iy = tile.worldy() - Vars.tilesize / 2f - ORE_ICON_SIZE * 0.5f;

                Draw.color(Color.white, 0.85f);
                Draw.rect(icon, ix, iy, ORE_ICON_SIZE, ORE_ICON_SIZE);
            }
        }
        Draw.reset();
    }
}

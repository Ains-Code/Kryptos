package kryptos.content;

import arc.Core;
import arc.Events;
import arc.math.Rand;
import mindustry.content.Blocks;
import mindustry.content.Planets;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.world.Tile;

import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * Vanilla campaign generators (Serpulo/Erekir) hardcode their own ore lists,
 * so a modded ore like voidsteel never appears there even with
 * {@code oreDefault = true}. This sprinkles a sparse, spawn-safe scatter of
 * voidsteel into a sector's terrain automatically on load, no HUD toggle
 * needed. A per-sector flag (saved in settings, not the map) makes sure it
 * only runs once each -- this also retroactively covers sectors you already
 * visited before this existed, since it just checks "have I injected this
 * sector id yet?" rather than "is this a brand new sector?".
 */
public class KryptosOreInjector {
    private static final String FLAG_PREFIX = "kryptos-ore-injected-";
    private static final float CHANCE = 0.03f;
    private static final float MIN_SPAWN_DIST = 45f * tilesize;

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> {
            var sector = state.rules.sector;
            if (sector == null || sector.planet == null) {
                return;
            }
            if (sector.planet != Planets.serpulo && sector.planet != Planets.erekir) {
                return;
            }

            String flag = FLAG_PREFIX + sector.planet.name + "-" + sector.id;
            if (Core.settings.getBool(flag, false)) {
                return;
            }

            inject(sector.id);
            Core.settings.put(flag, true);
        });
    }

    private static void inject(int sectorId) {
        if (KryptosBlocks.oreVoidsteel == null) {
            return;
        }

        Rand rand = new Rand(sectorId * 9781L + 13);

        float spawnX = -1, spawnY = -1;
        Building core = state.rules.defaultTeam.core();
        if (core != null) {
            spawnX = core.x;
            spawnY = core.y;
        }

        for (int x = 0; x < world.width(); x++) {
            for (int y = 0; y < world.height(); y++) {
                Tile tile = world.tile(x, y);
                if (tile == null || !tile.overlay().isAir() || tile.floor().isLiquid) {
                    continue;
                }
                if (tile.block() != Blocks.air && tile.block().isStatic()) {
                    continue;
                }
                if (spawnX >= 0) {
                    float dx = tile.worldx() - spawnX;
                    float dy = tile.worldy() - spawnY;
                    if (dx * dx + dy * dy < MIN_SPAWN_DIST * MIN_SPAWN_DIST) {
                        continue;
                    }
                }
                if (rand.chance(CHANCE)) {
                    tile.setOverlay(KryptosBlocks.oreVoidsteel);
                }
            }
        }
    }
}

package kryptos.content;

import mindustry.content.Fx;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.gen.ElevationMoveUnit;
import mindustry.type.UnitType;
import mindustry.type.Weapon;

/**
 * Content-side unit definitions for Kryptos.
 *
 * {@link #strider} is built by {@link KryptosBlocks#factory} from Kryptos ore
 * and is a genuine ground/air hybrid: it walks normally with real ground
 * pathfinding and collision, but can also boost into full flight (ignoring
 * solid terrain entirely) on command.
 *
 * This is done with zero custom entity components and zero build.gradle
 * changes -- {@code constructor = ElevationMoveUnit::create} is a stock,
 * precompiled Mindustry class (see UnitType#checkEntityMapping's list of
 * vanilla default constructors: "hover" -> ElevationMoveUnit::create). The
 * actual ground<->air toggle is elevation (Unit#isGrounded/isFlying), driven
 * by {@code canBoost}: with {@code flying = false} the unit starts grounded
 * (elevation 0, real solidity checks), and boosting ramps elevation to 1
 * (ElevationMoveComp#solidity() then returns null, i.e. ignores all solid
 * tiles -- full flight). Both the standard player unit-command UI and RTS
 * command AI already expose a boost toggle for any unit with
 * {@code canBoost = true} (see CommandAI/BoostAI upstream), so no custom
 * controller is needed here either.
 *
 * The old {@code defenseBuilder} type (used only by the now-removed
 * KryptosPerimeterDefense/KryptosBuilderUnits/KryptosDroneAI automation) is
 * gone; its placeholder sprite has been repurposed as {@code strider}'s body
 * art (sprites/units/kryptos-strider.png) since no dedicated art exists yet.
 */
public class KryptosUnits {
    public static UnitType strider;

    public static void load() {
        strider = new UnitType("kryptos-strider") {{
            // "hover" preset -- see class doc above. No legs/mech animation,
            // matching the current placeholder sprite (a drone-like hull,
            // not a legged mech).
            constructor = ElevationMoveUnit::create;

            flying = false;
            canBoost = true;
            riseSpeed = 0.06f;
            descentSpeed = 0.06f;

            targetGround = true;
            targetAir = true;

            hitSize = 10f;
            health = 260f;

            speed = 1.35f;
            accel = 0.4f;
            drag = 0.35f;
            rotateSpeed = 6f;

            engineOffset = 6.5f;
            engineSize = 2.1f;

            // Destructive splash-damage weapon: reuses the vanilla "bullet"
            // sprite region (BasicBulletType's default) so no extra bullet
            // art is needed. Weapon mount sprite is a small placeholder
            // (sprites/units/kryptos-strider-weapon.png) -- swap later
            // without touching this file.
            weapons.add(new Weapon("kryptos-strider-weapon") {{
                x = 5f;
                y = 1f;
                reload = 40f;
                bullet = new BasicBulletType(6.5f, 28f) {{
                    width = 10f;
                    height = 14f;
                    lifetime = 40f;
                    hitEffect = Fx.blastExplosion;
                    despawnEffect = Fx.none;
                    splashDamage = 55f;
                    splashDamageRadius = 24f;
                }};
            }});
        }};
    }
}

package kryptos.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import kryptos.automation.KryptosSmartDrill;
import mindustry.Vars;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;

/**
 * Per-drill-type settings for {@link KryptosSmartDrill}, ported from
 * Ains-Code/mod-mindustry's SmartDrillSettingDialog. One row per
 * Drill/BeamDrill block in the game: a "fill entire ore patch" toggle
 * (ignores the max-tiles cap, up to KryptosSmartDrill's own safety ceiling),
 * and otherwise a max-tiles slider (20-200). Backed by the same
 * Core.settings keys KryptosSmartDrill.getMaxTiles()/isFillAll() read.
 *
 * Opened from the gear button next to the Smart Drill toggle in
 * {@link KryptosAutomationPanel}.
 */
public class KryptosSmartDrillSettingDialog extends BaseDialog {

    public KryptosSmartDrillSettingDialog() {
        super(Core.bundle.get("kryptos.smart-drill.settings", "Smart Drill Settings"));
        addCloseButton();
        setup();
    }

    private void setup() {
        cont.clear();
        cont.pane(t -> {
            t.margin(10);
            for (Block block : Vars.content.blocks()) {
                if (block instanceof Drill || block instanceof BeamDrill) {
                    buildDrillSetting(t, block);
                }
            }
        }).grow();
    }

    private void buildDrillSetting(Table table, Block drill) {
        boolean fillAll = KryptosSmartDrill.isFillAll(drill);

        table.table(Styles.black6, t -> {
            t.top().left().margin(10);

            t.table(header -> {
                header.left();
                header.image(drill.uiIcon).size(32).padRight(10);
                header.add(drill.localizedName).growX().left();
            }).growX().row();

            t.image().color(Color.gray).growX().height(2f).padTop(5).padBottom(5).row();

            t.check(Core.bundle.get("kryptos.smart-drill.fill-all", "Fill Entire Ore Patch"), fillAll, checked -> {
                Core.settings.put("kryptos.smart-drill.fill-all." + drill.name, checked);
                setup();
            }).left().padBottom(5).row();

            if (fillAll) {
                t.add(Core.bundle.get("kryptos.smart-drill.fill-all.description",
                        "Ignores the tile limit and covers the whole connected ore vein."))
                    .left().color(Color.lightGray).wrap().growX().row();
            } else {
                t.table(configs -> {
                    configs.left();

                    configs.add(Core.bundle.get("kryptos.smart-drill.max-tiles", "Max Tiles")).left().padRight(10);
                    configs.label(() -> String.valueOf(KryptosSmartDrill.getMaxTiles(drill))).padRight(10).width(40);

                    configs.slider(20, 200, 1, KryptosSmartDrill.getMaxTiles(drill), slider -> {
                        Core.settings.put("kryptos.smart-drill.max-tiles." + drill.name, (int) slider);
                    }).growX();
                    configs.row();
                }).growX();
            }
        }).width(Math.min(Core.graphics.getWidth() * 0.9f, 450f)).pad(5).row();
    }
}

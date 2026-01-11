package customunit;

import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.ui.dialogs.*;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import customunit.blocks.unit.DerivativeUnitFactory;

public class CustomUnitMod extends Mod{

    public CustomUnitMod(){
        Log.info("Loaded CustomUnitMod constructor.");
    }

    @Override
    public void loadContent(){
        Log.info("Loading CustomUnitMod content.");
        
        // 注册"量子.虚幻"建筑
        new DerivativeUnitFactory("finalF"){{
            requirements(Category.units, ItemStack.with(Items.silicon, 6000, Items.thorium, 4000, Items.phaseFabric, 3000, Items.surgeAlloy, 3000));
            size = 5;
            consumePower(40);
            alwaysUnlocked = true;
            liquidCapacity = 60;
            placeableLiquid = true;
            floating = true;
        }};
    }

}
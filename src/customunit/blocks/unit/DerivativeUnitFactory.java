package customunit.blocks.unit;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.struct.EnumSet;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.game.EventType;
import mindustry.game.Schematic;
import mindustry.game.Team;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.world.Tile;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Schematics;
import mindustry.world.Block;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.*;
import static arc.graphics.g2d.Draw.color;

public class DerivativeUnitFactory extends UnitFactory {
    public int areaSize = 14;
    public Effect aboveEffect = new Effect(24, e -> {
        color(e.color);
        Fill.circle(e.x, e.y, e.rotation * e.fout());
    });
    
    public DerivativeUnitFactory(String name) {
        super(name);

        ambientSound = null;
        ambientSoundVolume = 0.1f;
        flags = EnumSet.of(BlockFlag.factory);
    }

    @Override
    public void init() {
        super.init();
        for(UnitPlan plan : plans){
            areaSize = Math.max((int) plan.unit.hitSize/tilesize, areaSize);
        }
    }

    public Rect getRect(Rect rect, float x, float y, int rotation){
        rect.setCentered(x, y, areaSize * tilesize);
        float len = tilesize * (areaSize + size)/2f;

        rect.x += Geometry.d4x(rotation) * len;
        rect.y += Geometry.d4y(rotation) * len;

        return rect;
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);

        x *= tilesize;
        y *= tilesize;
        x += offset;
        y += offset;

        Rect rect = getRect(Tmp.r1, x, y, rotation);

        Drawf.dashRect(valid ? Pal.accent : Pal.remove, rect);
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation){
        //same as UnitAssembler
        Rect rect = getRect(Tmp.r1, tile.worldx() + offset, tile.worldy() + offset, rotation).grow(0.1f);
        return !indexer.getFlagged(team, BlockFlag.factory).contains(b -> b instanceof DerivativeBuild && getRect(Tmp.r2, b.x, b.y, b.rotation).overlaps(rect));
    }

    public class DerivativeBuild extends UnitFactoryBuild {
        public Vec2 v1 = new Vec2();
        public Vec2 v2 = new Vec2();
        public Vec2 offset = new Vec2(), end = new Vec2();

        private final Object[] objects = new Object[4];
        
        // 变量a：保存完整地图状态（建筑、墙、地板）
        private Schematic mapStateA;
        // 变量b：保存当前建筑状态
        private Schematic mapStateB;
        
        // 开关状态
        private boolean isActive = false;
        
        // 14*14区域的大小
        private static final int REGION_SIZE = 14;
        
        // 日志文件路径
        private static final String LOG_PATH = "E:\\SteamLibrary\\steamapps\\common\\Mindustry\\nestedlogic.log";
        
        // 日志记录方法
        private void log(String message) {
            try {
                Fi logFile = Core.files.absolute(LOG_PATH);
                logFile.writeString("[" + System.currentTimeMillis() + "] " + message + "\n", true);
            } catch (Exception e) {
                Log.err("Failed to write log: " + e.getMessage());
            }
        }

        public Vec2 getUnitSpawn(){
            float len = tilesize * (areaSize + size)/2f;
            float unitX = x + Geometry.d4x(rotation) * len;
            float unitY = y + Geometry.d4y(rotation) * len;
            v2.set(unitX, unitY);
            return v2;
        }

        @Override
        public void updateTile() {
            if(!configurable){
                currentPlan = 0;
            }

            if(currentPlan < 0 || currentPlan >= plans.size){
                currentPlan = -1;
            }

            if(efficiency > 0 && currentPlan != -1){
                time += edelta() * speedScl * Vars.state.rules.unitBuildSpeed(team);
                progress += edelta() * Vars.state.rules.unitBuildSpeed(team);
                speedScl = Mathf.lerpDelta(speedScl, 1f, 0.05f);
            }else{
                speedScl = Mathf.lerpDelta(speedScl, 0f, 0.05f);
            }

            moveOutPayload();

            if(currentPlan != -1 && payload == null){
                UnitPlan plan = plans.get(currentPlan);

                //make sure to reset plan when the unit got banned after placement
                if(plan.unit.isBanned()){
                    currentPlan = -1;
                    return;
                }

                if(progress >= plan.time){
                    progress %= 1f;

                    Unit unit = plan.unit.create(team);
                    if(unit.type != null) {
                        Vec2 v = getUnitSpawn();
                        float dst = v.dst(this);
                        float a = angleTo(v);
                        objects[0] = unit.type.fullIcon;
                        objects[1] = dst;
                        objects[2] = 90f * rotation - 90f;
                        objects[3] = 180f;
                    }
                    if(commandPos != null && unit.isCommandable()){
                        unit.command().commandPosition(commandPos);
                    }
                    payload = new UnitPayload(unit);
                    payVector.setZero();
                    consume();
                    Events.fire(new EventType.UnitCreateEvent(payload.unit, this));
                }

                progress = Mathf.clamp(progress, 0, plan.time);
            }else{
                progress = 0f;
            }
        }

        @Override
        public void draw() {
            Draw.rect(region, x, y);
            Draw.rect(outRegion, x, y, rotdeg());
            Draw.rect(topRegion, x, y);

            Vec2 v = getUnitSpawn();
            float z = Draw.z();
            if(currentPlan != -1) {
                UnitPlan plan = plans.get(currentPlan);
                Draw.draw(Layer.blockOver, () -> Drawf.construct(v.x, v.y, plan.unit.fullIcon, rotdeg() - 90f, progress / plan.time + 0.05f, speedScl, time));
                if(efficiency > 0.001f) {
                    Draw.color(Pal.accent);
                    Draw.z(Layer.buildBeam);
                    Fill.circle(x, y, 3 * efficiency * speedScl);
                    Drawf.buildBeam(x, y, v.x, v.y, plan.unit.hitSize / 2f * efficiency * speedScl);

                    if(plan.unit != null) {
                        Draw.z(Layer.effect);
                        Fill.circle(x, y, 1.8f * efficiency * speedScl);
                        Lines.stroke(2.5f * efficiency * speedScl);
                        for(int i = 1; i <= 3; i++){
                            end.set(v).sub(x, y);
                            end.setLength(Math.max(2f, end.len()));
                            end.add(offset.trns(
                                    time/2 + 60 * i,
                                    Mathf.sin(time * 2 + 30 * i, 50f, plan.unit.hitSize * 0.6f)
                            ));
                            end.add(x, y);
                            Lines.line(x, y, end.x, end.y);
                            aboveEffect.at(end.x, end.y, 2, Pal.accent);
                            if(!state.isPaused() && Mathf.chance(0.01f)) {
                                Fx.hitLancer.at(end);
                                Sounds.shoot.at(end.x, end.y, 0.5f, 0.3f);
                            }
                        }
                        Draw.color(team.color);
                        Lines.arc(v.x, v.y, plan.unit.hitSize * 1.2f, 1 - progress / plan.time, rotation * 90);
                        control.sound.loop(ambientSound, self(), ambientSoundVolume * speedScl * efficiency);
                        for(int i = 0; i < 2; i++){
                            float rot = rotation * 90 - 90 + 180 * i;
                            float ax = v.x + Angles.trnsx(rot, plan.unit.hitSize * 1.1f);
                            float ay = v.y + Angles.trnsy(rot, plan.unit.hitSize * 1.1f);
                            for(int a = 0; a < 3; a++){
                                float sin = Math.max(0, Mathf.sin(time + a * 60f, 55f, 1f)) * speedScl;
                                Draw.rect(
                                        Core.atlas.find("aim-shoot"),
                                        ax + Angles.trnsx(rot + 180, -4) * (tilesize / 2f + a * 2.8f),
                                        ay + Angles.trnsy(rot + 180, -4) * (tilesize / 2f + a * 2.8f),
                                        45f * sin,
                                        45f * sin,
                                        rot + 90
                                );
                            }
                        }
                    }
                }
            }

            Draw.z(z);
            Draw.reset();
        }

        @Override
        public void drawSelect() {
            super.drawSelect();
            Drawf.dashRect(Pal.accent, getRect(Tmp.r1, x, y, rotation));
        }

        @Override
        public void buildConfiguration(arc.scene.ui.layout.Table table) {
            // 替换默认的单位选择列表为一个简单的按钮
            table.button("量子.虚幻", () -> {
                // 按钮点击事件
                if (!isActive) {
                    // 打开方法
                    openQuantumUnreal();
                } else {
                    // 关闭方法
                    closeQuantumUnreal();
                }
                isActive = !isActive;
            }).size(200, 50);
        }
        
        // 打开方法：保存状态到变量a，清除画板，绘制14*14区域+变量b的建筑
        private void openQuantumUnreal() {
            log("=== OPEN QUANTUM UNREAL ===");
            
            // 1. 保存当前状态到变量a
            mapStateA = captureRegionState();
            
            // 2. 清除并绘制14*14区域
            clearAndDraw14x14Region();
            
            // 3. 绘制变量b中的建筑（如果有）
            if (mapStateB != null) {
                log("Open - Drawing buildings from state B, count: " + mapStateB.tiles.size);
                placeSchematic(mapStateB);
            } else {
                log("Open - No buildings in state B");
            }
            
            log("=== OPEN COMPLETE ===");
        }
        
        // 关闭方法：保存当前建筑到变量b，清除画板，还原变量a的状态
        private void closeQuantumUnreal() {
            log("=== CLOSE QUANTUM UNREAL ===");
            
            // 1. 保存当前建筑到变量b
            mapStateB = captureRegionBuildings();
            
            // 2. 还原变量a的状态
            if (mapStateA != null) {
                log("Close - Restoring state A, block count: " + mapStateA.tiles.size);
                placeSchematic(mapStateA);
            } else {
                log("Close - No state A to restore");
            }
            
            log("=== CLOSE COMPLETE ===");
        }
        
        // 使用游戏蓝图系统捕获区域完整状态
        private Schematic captureRegionState() {
            log("=== CAPTURE REGION STATE ===");
            
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            log("CaptureState - Start X: " + startX + ", Start Y: " + startY);
            
            // 创建Schematic的tiles列表
            Seq<Schematic.Stile> tiles = new Seq<>();
            
            // 遍历区域内所有瓦片，保存完整状态
            for (int i = 0; i < REGION_SIZE; i++) {
                for (int j = 0; j < REGION_SIZE; j++) {
                    int worldX = startX + i;
                    int worldY = startY + j;
                    
                    if (worldX >= 0 && worldX < world.width() && worldY >= 0 && worldY < world.height()) {
                        Tile tile = world.tile(worldX, worldY);
                        if (tile != null) {
                            Block block = tile.block();
                            
                            // 只保存非空气方块
                            if (block != Blocks.air) {
                                // 计算相对于区域左上角的本地坐标
                                int localX = i;
                                int localY = j;
                                
                                // 保存建筑配置和旋转
                                Object config = tile.build != null ? tile.build.config() : null;
                                byte rotation = tile.build != null ? (byte) tile.build.rotation : 0;
                                
                                // 添加到Schematic中
                                tiles.add(new Schematic.Stile(block, localX, localY, config, rotation));
                                
                                log("CaptureState - Saved: " + block.name + 
                                    " at " + localX + "," + localY + 
                                    " (world: " + worldX + "," + worldY + ")");
                            }
                        }
                    }
                }
            }
            
            log("CaptureState - Total blocks saved: " + tiles.size);
            return new Schematic(tiles, new StringMap(), REGION_SIZE, REGION_SIZE);
        }
        
        // 使用游戏蓝图系统捕获区域内的建筑
        private Schematic captureRegionBuildings() {
            log("=== CAPTURE REGION BUILDINGS ===");
            
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            log("CaptureBuildings - Start X: " + startX + ", Start Y: " + startY);
            
            // 创建Schematic的tiles列表
            Seq<Schematic.Stile> tiles = new Seq<>();
            
            // 遍历区域内所有瓦片，只保存有build的建筑
            for (int i = 0; i < REGION_SIZE; i++) {
                for (int j = 0; j < REGION_SIZE; j++) {
                    int worldX = startX + i;
                    int worldY = startY + j;
                    
                    if (worldX >= 0 && worldX < world.width() && worldY >= 0 && worldY < world.height()) {
                        Tile tile = world.tile(worldX, worldY);
                        if (tile != null && tile.build != null) {
                            Block block = tile.block();
                            
                            // 计算相对于区域左上角的本地坐标
                            int localX = i;
                            int localY = j;
                            
                            // 保存建筑配置和旋转
                            Object config = tile.build.config();
                            byte rotation = (byte) tile.build.rotation;
                            
                            // 添加到Schematic中
                            tiles.add(new Schematic.Stile(block, localX, localY, config, rotation));
                            
                            log("CaptureBuildings - Saved: " + block.name + 
                                " at " + localX + "," + localY + 
                                " (world: " + worldX + "," + worldY + ")");
                        }
                    }
                }
            }
            
            log("CaptureBuildings - Total buildings saved: " + tiles.size);
            return new Schematic(tiles, new StringMap(), REGION_SIZE, REGION_SIZE);
        }
        
        // 清除并绘制14*14区域
        private void clearAndDraw14x14Region() {
            log("=== CLEAR AND DRAW 14x14 REGION ===");
            
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            // 清除并绘制区域
            for (int i = 0; i < REGION_SIZE; i++) {
                for (int j = 0; j < REGION_SIZE; j++) {
                    int worldX = startX + i;
                    int worldY = startY + j;
                    
                    if (worldX >= 0 && worldX < world.width() && worldY >= 0 && worldY < world.height()) {
                        Tile tile = world.tile(worldX, worldY);
                        if (tile != null) {
                            // 清除现有内容
                            tile.remove();
                            
                            // 绘制边框（暗金属墙）
                            if (i == 0 || i == REGION_SIZE - 1 || j == 0 || j == REGION_SIZE - 1) {
                                tile.setBlock(Blocks.darkMetal, team);
                            } else {
                                // 绘制中间（暗面板3）
                                tile.setBlock(Blocks.darkPanel3, team);
                            }
                        }
                    }
                }
            }
        }
        
        // 使用游戏内置方法放置蓝图
        private void placeSchematic(Schematic schematic) {
            log("=== PLACE SCHEMATIC ===");
            
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            log("PlaceSchematic - Start X: " + startX + ", Start Y: " + startY);
            
            // 遍历蓝图中的所有瓦片
            for (Schematic.Stile stile : schematic.tiles) {
                // 计算世界坐标
                int worldX = startX + stile.x;
                int worldY = startY + stile.y;
                
                Tile tile = world.tile(worldX, worldY);
                if (tile != null) {
                    // 使用游戏内置方法处理多瓦片建筑的清除和放置
                    Seq<Tile> linked = new Seq<>();
                    tile.getLinkedTilesAs(stile.block, linked);
                    
                    // 清除目标位置的现有建筑
                    for (Tile t : linked) {
                        if (t.block() != Blocks.air) {
                            t.remove();
                        }
                    }
                    
                    // 放置新建筑
                    tile.setBlock(stile.block, team, stile.rotation);
                    
                    // 应用配置
                    if (stile.config != null && tile.build != null) {
                        tile.build.configureAny(stile.config);
                    }
                    
                    log("PlaceSchematic - Placed: " + stile.block.name + 
                        " at " + worldX + "," + worldY + 
                        " Rotation: " + stile.rotation);
                }
            }
        }
        
        // 获取14*14区域的起始X坐标
        private int getRegionStartX() {
            int tileX = tileX();
            int dir = rotation;
            float len = tilesize * (areaSize + size)/2f;
            int offsetX = Math.round(-Geometry.d4x(dir) * len / tilesize);
            return tileX + offsetX - REGION_SIZE / 2;
        }
        
        // 获取14*14区域的起始Y坐标
        private int getRegionStartY() {
            int tileY = tileY();
            int dir = rotation;
            float len = tilesize * (areaSize + size)/2f;
            int offsetY = Math.round(-Geometry.d4y(dir) * len / tilesize);
            return tileY + offsetY - REGION_SIZE / 2;
        }
    }
}
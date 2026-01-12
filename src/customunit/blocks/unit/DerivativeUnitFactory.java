package customunit.blocks.unit;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.struct.EnumSet;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.Effect;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.world.Tile;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Schematic;
import mindustry.world.Block;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.meta.BlockFlag;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.*;
import static arc.graphics.g2d.Draw.color;

public class DerivativeUnitFactory extends UnitFactory {
    public int areaSize = 14;
    public Effect aboveEffect = new Effect(24, e -> {
        color(e.color);
        Fill.circle(e.x, e.y, e.rotation * e.fout());
    });
    
    // 使用游戏内置的Schematic类保存建筑计划
    protected Schematic mapStateA;
    protected Schematic mapStateB;
    
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
            // 1. 保存当前状态到变量a
            saveStateA();
            
            // 2. 清除画板
            clearRegion();
            
            // 3. 绘制14*14区域
            draw14x14Region();
            
            // 4. 绘制变量b中的建筑（如果有）
            if (mapStateB != null) {
                drawBuildingFromStateB();
            }
        }
        
        // 关闭方法：保存当前建筑到变量b，清除画板，还原变量a的状态
        private void closeQuantumUnreal() {
            // 1. 保存当前建筑到变量b
            saveStateB();
            
            // 2. 清除画板
            clearRegion();
            
            // 3. 还原变量a的状态
            restoreStateA();
        }
        
        // 保存完整地图状态到变量a
        private void saveStateA() {
            // 获取14*14区域的起始坐标
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            // 创建Schematic的tiles列表
            Seq<Schematic.Stile> tiles = new Seq<>();
            
            // 用于避免重复保存同一个建筑
            IntSet counted = new IntSet();
            
            // 保存每个瓦片的状态
            for (int i = 0; i < REGION_SIZE; i++) {
                for (int j = 0; j < REGION_SIZE; j++) {
                    int worldX = startX + i;
                    int worldY = startY + j;
                    if (worldX >= 0 && worldX < world.width() && worldY >= 0 && worldY < world.height()) {
                        Tile tile = world.tile(worldX, worldY);
                        if (tile != null) {
                            Block block = tile.block();
                            
                            // 只保存非空气方块，且未被计数过
                            if (block != Blocks.air && !counted.contains(tile.pos())) {
                                // 计算相对于区域左上角的本地坐标
                                int localX = i;
                                int localY = j;
                                
                                Object config = tile.build != null ? tile.build.config() : null;
                                byte rotation = tile.build != null ? (byte)tile.build.rotation : 0;
                                
                                // 添加到Schematic中
                                tiles.add(new Schematic.Stile(block, localX, localY, config, rotation));
                                
                                // 标记该建筑的所有瓦片为已计数
                                Seq<Tile> linked = new Seq<>();
                                tile.getLinkedTilesAs(block, linked);
                                for (Tile linkedTile : linked) {
                                    counted.add(linkedTile.pos());
                                }
                            }
                        }
                    }
                }
            }
            
            // 创建Schematic对象保存整个地图状态
            mapStateA = new Schematic(tiles, new StringMap(), REGION_SIZE, REGION_SIZE);
        }
        
        // 保存当前建筑状态到变量b
        private void saveStateB() {
            // 获取14*14区域的起始坐标
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            // 创建Schematic的tiles列表
            Seq<Schematic.Stile> tiles = new Seq<>();
            
            // 用于避免重复保存同一个建筑
            IntSet counted = new IntSet();
            
            // 保存区域内所有建筑的状态
            for (int i = 0; i < REGION_SIZE; i++) {
                for (int j = 0; j < REGION_SIZE; j++) {
                    int worldX = startX + i;
                    int worldY = startY + j;
                    if (worldX >= 0 && worldX < world.width() && worldY >= 0 && worldY < world.height()) {
                        Tile tile = world.tile(worldX, worldY);
                        if (tile != null && tile.build != null && !counted.contains(tile.pos())) {
                            // 保存建筑状态
                            Block buildingType = tile.block();
                            int rotation = tile.build.rotation;
                            Object config = tile.build.config();
                            
                            // 计算相对于区域左上角的本地坐标
                            int localX = i;
                            int localY = j;
                            
                            // 创建Schematic.Stile对象
                            tiles.add(new Schematic.Stile(buildingType, localX, localY, config, (byte)rotation));
                            
                            // 标记该建筑的所有瓦片为已计数
                            Seq<Tile> linked = new Seq<>();
                            tile.getLinkedTilesAs(buildingType, linked);
                            for (Tile linkedTile : linked) {
                                counted.add(linkedTile.pos());
                            }
                        }
                    }
                }
            }
            
            // 创建Schematic对象保存当前建筑状态
            mapStateB = new Schematic(tiles, new StringMap(), REGION_SIZE, REGION_SIZE);
        }
        
        // 清除14*14区域
        private void clearRegion() {
            // 获取14*14区域的起始坐标
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            // 清除区域内的所有内容
            for (int i = 0; i < REGION_SIZE; i++) {
                for (int j = 0; j < REGION_SIZE; j++) {
                    int worldX = startX + i;
                    int worldY = startY + j;
                    if (worldX >= 0 && worldX < world.width() && worldY >= 0 && worldY < world.height()) {
                        Tile tile = world.tile(worldX, worldY);
                        if (tile != null) {
                            // 清除瓦片内容
                            // 1. 清除建筑（如果有）
                            if (tile.build != null) {
                                tile.remove();
                            }
                            
                            // 2. 清除所有非地板方块（保留地板）
                            if (tile.block() != null && !(tile.block() instanceof Floor)) {
                                tile.setAir();
                            }
                        }
                    }
                }
            }
        }
        
        // 绘制14*14区域：4边为暗金属墙，中间为暗面板3
        private void draw14x14Region() {
            // 获取14*14区域的起始坐标
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            // 绘制区域
            for (int i = 0; i < REGION_SIZE; i++) {
                for (int j = 0; j < REGION_SIZE; j++) {
                    int worldX = startX + i;
                    int worldY = startY + j;
                    if (worldX >= 0 && worldX < world.width() && worldY >= 0 && worldY < world.height()) {
                        Tile tile = world.tile(worldX, worldY);
                        if (tile != null) {
                            // 绘制边框（暗金属墙）
                            if (i == 0 || i == REGION_SIZE - 1 || j == 0 || j == REGION_SIZE - 1) {
                                tile.setBlock(Blocks.darkMetal);
                            } else {
                                // 绘制中间（暗面板3）
                                tile.setFloor((Floor) Blocks.darkPanel3);
                            }
                        }
                    }
                }
            }
        }
        
        // 从变量b中绘制建筑
        private void drawBuildingFromStateB() {
            if (mapStateB == null) return;
            
            // 获取14*14区域的起始坐标
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            // 遍历Schematic中的所有瓦片
            for (Schematic.Stile stile : mapStateB.tiles) {
                // 计算实际世界坐标
                int worldX = startX + stile.x;
                int worldY = startY + stile.y;
                
                // 获取对应位置的Tile对象
                Tile tile = world.tile(worldX, worldY);
                if (tile != null) {
                    // 清除当前位置的建筑
                    if (tile.build != null) {
                        tile.remove();
                    }
                    
                    // 创建新建筑
                    tile.setBlock(stile.block, team, stile.rotation);
                    
                    // 应用配置信息
                    if (stile.config != null && tile.build != null) {
                        tile.build.configure(stile.config);
                    }
                }
            }
        }
        
        // 还原变量a的状态
        private void restoreStateA() {
            if (mapStateA == null) return;
            
            // 获取14*14区域的起始坐标
            int startX = getRegionStartX();
            int startY = getRegionStartY();
            
            // 1. 先将所有瓦片重置为空气，清除所有内容
            clearRegion();
            
            // 2. 还原状态
            for (Schematic.Stile stile : mapStateA.tiles) {
                // 计算实际世界坐标
                int worldX = startX + stile.x;
                int worldY = startY + stile.y;
                
                // 获取对应位置的Tile对象
                Tile tile = world.tile(worldX, worldY);
                if (tile != null) {
                    // 设置新的建筑或墙
                    tile.setBlock(stile.block, team, stile.rotation);
                    
                    // 应用配置信息
                    if (stile.config != null && tile.build != null) {
                        tile.build.configure(stile.config);
                    }
                }
            }
        }
        
        // 获取14*14区域的起始X坐标
        private int getRegionStartX() {
            // 获取建筑的瓦片坐标
            int tileX = tileX();
            int tileY = tileY();
            
            // 建筑背方是虚线框的对面
            // 虚线框在建筑前方，所以背方是相反方向
            int dir = rotation;
            
            // 计算背方的偏移量（与虚线框相反方向）
            // 虚线框偏移：tilesize * (areaSize + size)/2f
            // 背方偏移：与虚线框相反方向，所以使用负的方向向量
            float len = tilesize * (areaSize + size)/2f;
            int offsetX = Math.round(-Geometry.d4x(dir) * len / tilesize);
            int offsetY = Math.round(-Geometry.d4y(dir) * len / tilesize);
            
            // 计算14*14区域的起始坐标，确保区域在建筑背方
            int startX = tileX + offsetX - REGION_SIZE / 2;
            int startY = tileY + offsetY - REGION_SIZE / 2;
            
            return startX;
        }
        
        // 获取14*14区域的起始Y坐标
        private int getRegionStartY() {
            // 获取建筑的瓦片坐标
            int tileX = tileX();
            int tileY = tileY();
            
            // 建筑背方是虚线框的对面
            int dir = rotation;
            
            // 计算背方的偏移量
            float len = tilesize * (areaSize + size)/2f;
            int offsetX = Math.round(-Geometry.d4x(dir) * len / tilesize);
            int offsetY = Math.round(-Geometry.d4y(dir) * len / tilesize);
            
            // 计算14*14区域的起始坐标
            int startX = tileX + offsetX - REGION_SIZE / 2;
            int startY = tileY + offsetY - REGION_SIZE / 2;
            
            return startY;
        }
    }
}
package com.gtoelevatorfix;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 太空電梯保活。
 *
 * <p><b>要治的病</b>：太空電梯的常駐運轉靠 {@code SpaceElevatorMachine.onWorking()} 把進度回捲
 * （到 190 就設回 1）讓配方永遠跑不完；但回捲的守衛條件讀的是 {@code RecipeLogic.lastOriginRecipe}，
 * 那是<b>瞬態欄位、不寫存檔</b>。重進遊戲後它是 null → 回捲失效 → 400 tick 的配方真的跑完 →
 * 結束那一 tick 本機已經實扣了自己那份算力，重新匹配時運算中心只給得出
 * {@code 最大算力 - 已占用}，算力沒有兩倍餘量就匹配失敗 → 進入 IDLE；
 * 而 {@code IRecipeLogicMachine.keepSubscribing()} 預設 false，{@code RecipeLogic} 隨即退訂 tick。
 * 太空電梯沒有任何會變化的物品／流體輸入來發出「內容變更」通知把它叫醒，於是永久停擺，
 * 只能玩家手動關機再開機。（算力／能量瞬時不足觸發的 {@code interruptRecipe()} 也會直接退訂，同一種死法。）
 *
 * <p><b>連鎖傷害</b>：電梯只要停一下，模組的 {@code SpaceElevatorModuleMachine.handleTickRecipe()}
 * 每 10 tick 檢查一次 {@code getSpaceElevatorTier() >= 8}，而該值在控制器沒在運轉時是 0 →
 * 模組立刻 {@code interruptRecipe()} → 同樣退訂 tick。電梯自己活過來，模組卻醒不了。
 *
 * <p><b>怎麼治</b>（三件事，各有獨立旗標）：
 * <ol>
 *   <li><b>保活</b>：每 {@code gtoelevatorfix.period}（預設 5）tick 對每一台已知的太空電梯控制器
 *       <b>與模組</b>呼叫 {@code getRecipeLogic().updateTickSubscription()}。那支方法自己會處理所有
 *       情況——已訂閱時是 no-op、玩家關機（SUSPEND）或結構未成型時會正確退訂——所以這裡不做任何
 *       狀態判斷，也不碰配方內容。</li>
 *   <li><b>開場清帳</b>：控制器第一次被納入追蹤時，若它處於「WORKING 但 lastOriginRecipe 是 null」
 *       這個<b>只有剛讀檔才會出現</b>的狀態，就呼叫一次 {@code resetRecipeLogic()} 逼它立刻重搜配方。
 *       這樣 {@code lastOriginRecipe} 馬上補回來、回捲恢復，那次「配方跑完 → 停一下」根本不會發生
 *       （否則會在讀檔後約 400 tick 才爆，模組必然跟著中斷）。</li>
 *   <li><b>模組保活</b>：把 {@code SpaceElevatorModuleMachine}（含資料模組、巨型模組等子類）
 *       一起納入第 1 點。</li>
 * </ol>
 *
 * <p><b>為什麼是反射</b>：工作區既有紀錄顯示，往 {@code com.gtocore.*} 掛 mixin 有過兩次
 * 「進了 jar、config 正常載入、零錯誤零警告、但實際沒生效」的前例（真因未明），
 * 因此這裡連 mixin 都不用，只走公開 API 的反射；同時也不必把整合包的 GTCEU 版本綁進 build。
 */
public final class ElevatorKeepAlive {

    private static final Logger LOG = LogManager.getLogger("gtoelevatorfix");

    /** 太空電梯控制器；{@code SuperSpaceElevatorMachine}（通天之路）是它的子類，一併涵蓋。 */
    private static final String ELEVATOR_CLASS = "com.gtocore.common.machine.multiblock.electric.space.SpaceElevatorMachine";
    /** 太空電梯模組；資料模組、巨型模組都是它的子類。 */
    private static final String MODULE_CLASS = "com.gtocore.common.machine.multiblock.electric.space.SpaceElevatorModuleMachine";
    private static final String MACHINE_BE_CLASS = "com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity";

    /** 單一行為單一旗標（工作區慣例：不做「一鍵全開」總開關）。 */
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("gtoelevatorfix.enabled"));
    /** 模組是否一起保活。電梯停一下就會把模組打斷，所以預設開。 */
    private static final boolean KEEP_MODULES = !"false".equalsIgnoreCase(System.getProperty("gtoelevatorfix.modules"));
    /** 讀檔後是否清掉「WORKING 但沒有 lastOriginRecipe」的殘局。關掉就退回 0.1.0 的純保活。 */
    private static final boolean RESET_STALE = !"false".equalsIgnoreCase(System.getProperty("gtoelevatorfix.resetStaleRecipe"));
    /** 保活間隔（tick）。越小，被打斷後的恢復窗口越短。 */
    private static final int PERIOD = Math.max(1, Integer.getInteger("gtoelevatorfix.period", 5));
    /** 區塊載入後延幾 tick 再掃一次；區塊剛載入時 BlockEntity 有可能還沒實體化。 */
    private static final int RESCAN_DELAY = 20;

    /** 追蹤中的機器（identity set，機器物件不保證有值語意的 equals）。 */
    private static final Set<Object> TRACKED = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 已經印過「喚醒」訊息的機器，避免同一台重複洗版。 */
    private static final Set<Object> REVIVE_LOGGED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Deque<PendingScan> PENDING = new ArrayDeque<>();

    private static int tickCounter;
    private static int reviveCount;

    private static boolean reflectionReady;
    private static boolean reflectionFailed;

    private static Class<?> machineBeClass;
    private static Class<?> elevatorClass;
    private static Class<?> moduleClass;
    private static Field metaMachineField;
    private static Method isInValidMethod;
    private static Method getRecipeLogicMethod;
    private static Method updateTickSubscriptionMethod;
    private static Method getStatusMethod;
    private static Method getLastOriginRecipeMethod;
    private static Method resetRecipeLogicMethod;
    private static int statusWorking;
    /** 診斷用，缺了也不影響保活本身。 */
    private static Field subscriptionField;
    private static Field stillSubscribedField;

    private ElevatorKeepAlive() {}

    public static void register() {
        if (!ENABLED) {
            LOG.info("{} 已停用（-Dgtoelevatorfix.enabled=false）。", GtoElevatorFixMod.TAG);
            return;
        }
        MinecraftForge.EVENT_BUS.addListener(ElevatorKeepAlive::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(ElevatorKeepAlive::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ElevatorKeepAlive::onServerStopped);
        LOG.info("{} 已載入：每 {} tick 保活太空電梯{}；讀檔殘局清理={}。停用用 -Dgtoelevatorfix.enabled=false。",
                GtoElevatorFixMod.TAG, PERIOD, KEEP_MODULES ? "與模組" : "（不含模組）", RESET_STALE);
    }

    // ---------------------------------------------------------------- 事件

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ChunkAccess chunk = event.getChunk();
        if (chunk instanceof LevelChunk levelChunk) scan(level, levelChunk);
        // 區塊剛載入時 BlockEntity 可能還沒從 NBT 實體化，延後再掃一次補漏。
        PENDING.add(new PendingScan(level, chunk.getPos().toLong(), tickCounter + RESCAN_DELAY));
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickCounter++;
        drainPendingScans();
        if (tickCounter % PERIOD == 0) keepAlive();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        if (reviveCount > 0) {
            LOG.info("{} 本場共喚醒睡死的配方邏輯 {} 次。", GtoElevatorFixMod.TAG, reviveCount);
        }
        TRACKED.clear();
        REVIVE_LOGGED.clear();
        PENDING.clear();
        tickCounter = 0;
        reviveCount = 0;
    }

    // ---------------------------------------------------------------- 掃描

    private static void drainPendingScans() {
        while (!PENDING.isEmpty() && PENDING.peek().dueTick() <= tickCounter) {
            PendingScan pending = PENDING.poll();
            ChunkPos pos = new ChunkPos(pending.chunkPos());
            LevelChunk chunk = pending.level().getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) scan(pending.level(), chunk);
        }
    }

    private static void scan(ServerLevel level, LevelChunk chunk) {
        if (!ensureReflection()) return;
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (blockEntities.isEmpty()) return;
        for (BlockEntity blockEntity : blockEntities.values()) {
            if (!machineBeClass.isInstance(blockEntity)) continue;
            try {
                Object machine = metaMachineField.get(blockEntity);
                boolean elevator = elevatorClass.isInstance(machine);
                boolean module = KEEP_MODULES && moduleClass.isInstance(machine);
                if (!elevator && !module) continue;
                if (!TRACKED.add(machine)) continue;

                LOG.info("{} 納入保活：{} @ {}（{}）", GtoElevatorFixMod.TAG,
                        machine.getClass().getSimpleName(), blockEntity.getBlockPos(),
                        level.dimension().location());
                if (elevator && RESET_STALE) clearStaleRecipe(machine);
            } catch (ReflectiveOperationException | RuntimeException e) {
                fail("讀取 MetaMachineBlockEntity.metaMachine 失敗", e);
                return;
            }
        }
    }

    /**
     * 讀檔殘局清理：「狀態是 WORKING、卻沒有 lastOriginRecipe」只可能是剛從存檔還原
     * （{@code lastOriginRecipe} 是瞬態欄位）。這種殘局撐到配方跑完就會停一下、順手打斷所有模組，
     * 所以在這裡直接重置配方邏輯，逼它立刻重搜一次、把 {@code lastOriginRecipe} 補回來。
     */
    private static void clearStaleRecipe(Object machine) throws ReflectiveOperationException {
        Object recipeLogic = getRecipeLogicMethod.invoke(machine);
        if (recipeLogic == null) return;
        if ((Integer) getStatusMethod.invoke(recipeLogic) != statusWorking) return;
        if (getLastOriginRecipeMethod.invoke(recipeLogic) != null) return;
        resetRecipeLogicMethod.invoke(recipeLogic);
        LOG.info("{} 清掉讀檔殘局：{} 的配方是從存檔還原、沒有 lastOriginRecipe，已重置為立即重搜，"
                + "避免它撐到配方跑完才停機並打斷模組。", GtoElevatorFixMod.TAG,
                machine.getClass().getSimpleName());
    }

    // ---------------------------------------------------------------- 保活

    private static void keepAlive() {
        if (TRACKED.isEmpty()) return;
        for (Iterator<Object> it = TRACKED.iterator(); it.hasNext();) {
            Object machine = it.next();
            try {
                if (Boolean.TRUE.equals(isInValidMethod.invoke(machine))) {
                    it.remove();
                    REVIVE_LOGGED.remove(machine);
                    continue;
                }
                Object recipeLogic = getRecipeLogicMethod.invoke(machine);
                if (recipeLogic == null) continue;

                boolean wasAsleep = isAsleep(recipeLogic);
                updateTickSubscriptionMethod.invoke(recipeLogic);
                if (wasAsleep && !isAsleep(recipeLogic)) onRevived(machine);
            } catch (ReflectiveOperationException | RuntimeException e) {
                fail("呼叫 RecipeLogic.updateTickSubscription() 失敗", e);
                return;
            }
        }
    }

    /**
     * 配方邏輯是不是已經退訂 tick。欄位讀不到時一律回 {@code false}——保活照做，只是少一條診斷訊息。
     */
    private static boolean isAsleep(Object recipeLogic) throws ReflectiveOperationException {
        if (subscriptionField == null || stillSubscribedField == null) return false;
        Object subscription = subscriptionField.get(recipeLogic);
        return subscription == null || !stillSubscribedField.getBoolean(subscription);
    }

    private static void onRevived(Object machine) {
        reviveCount++;
        if (REVIVE_LOGGED.add(machine)) {
            LOG.info("{} 喚醒了一台睡死的機器（{}）：配方邏輯原本已退訂 tick，"
                    + "這正是「算力歸零、要手動開關機器」的現場。", GtoElevatorFixMod.TAG,
                    machine.getClass().getSimpleName());
        } else {
            LOG.debug("{} 再次喚醒 {}（累計 {} 次）。", GtoElevatorFixMod.TAG,
                    machine.getClass().getSimpleName(), reviveCount);
        }
    }

    // ---------------------------------------------------------------- 反射

    private static boolean ensureReflection() {
        if (reflectionReady) return true;
        if (reflectionFailed) return false;
        try {
            ClassLoader loader = ElevatorKeepAlive.class.getClassLoader();
            machineBeClass = Class.forName(MACHINE_BE_CLASS, false, loader);
            elevatorClass = Class.forName(ELEVATOR_CLASS, false, loader);
            moduleClass = Class.forName(MODULE_CLASS, false, loader);

            metaMachineField = machineBeClass.getField("metaMachine");
            Class<?> metaMachineClass = metaMachineField.getType();
            isInValidMethod = metaMachineClass.getMethod("isInValid");
            // 宣告類別是 WorkableMultiblockMachine，控制器與模組都繼承它，同一支 Method 兩邊都能 invoke。
            getRecipeLogicMethod = elevatorClass.getMethod("getRecipeLogic");

            Class<?> recipeLogicClass = getRecipeLogicMethod.getReturnType();
            updateTickSubscriptionMethod = recipeLogicClass.getMethod("updateTickSubscription");
            getStatusMethod = recipeLogicClass.getMethod("getStatus");
            getLastOriginRecipeMethod = recipeLogicClass.getMethod("getLastOriginRecipe");
            resetRecipeLogicMethod = recipeLogicClass.getMethod("resetRecipeLogic");
            statusWorking = recipeLogicClass.getField("WORKING").getInt(null);

            // 以下兩個只用來判斷「原本是不是睡著」，拿不到就放棄診斷、不影響保活。
            try {
                subscriptionField = recipeLogicClass.getField("subscription");
                stillSubscribedField = subscriptionField.getType().getField("stillSubscribed");
            } catch (NoSuchFieldException e) {
                subscriptionField = null;
                stillSubscribedField = null;
                LOG.info("{} 讀不到 RecipeLogic.subscription，保活照常運作、只是不會印喚醒訊息。",
                        GtoElevatorFixMod.TAG);
            }

            reflectionReady = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("解析 GTCEU／GTOCore 類別失敗（整合包版本可能已改動 API）", e);
            return false;
        }
    }

    /** 任何一步失敗就整個停掉：這個 mod 的價值全在「不出事」，不容許半殘狀態每 tick 噴例外。 */
    private static void fail(String what, Throwable e) {
        reflectionFailed = true;
        reflectionReady = false;
        TRACKED.clear();
        PENDING.clear();
        LOG.error("{} {}；本場已停用保活。", GtoElevatorFixMod.TAG, what, e);
    }

    private record PendingScan(ServerLevel level, long chunkPos, int dueTick) {}
}

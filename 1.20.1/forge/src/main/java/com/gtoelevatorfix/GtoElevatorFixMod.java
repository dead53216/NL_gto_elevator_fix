package com.gtoelevatorfix;

import net.minecraftforge.fml.common.Mod;

/**
 * 修復 GregTech-Odyssey 太空電梯「重進遊戲後停機、算力占用歸零，必須手動開關機器才恢復」。
 *
 * <p>成因與實作細節寫在 {@code README.md}；本檔只做進入點。行為全部在
 * {@link ElevatorKeepAlive}：定期把太空電梯控制器的 {@code RecipeLogic} 重新掛回伺服器 tick。
 * 不改配方、不改產量、不改任何數值。
 */
@Mod(GtoElevatorFixMod.MODID)
public final class GtoElevatorFixMod {

    public static final String MODID = "gto_elevator_fix";

    /** log 前綴：沿用工作區「一個 mod 一個可 grep 前綴」的慣例。 */
    public static final String TAG = "[elevatorfix]";

    public GtoElevatorFixMod() {
        ElevatorKeepAlive.register();
    }
}

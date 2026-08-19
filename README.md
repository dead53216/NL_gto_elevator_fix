# NL_gto_elevator_fix

GregTech-Odyssey 專用小修補：**太空電梯重進遊戲後停機、算力占用歸零，必須手動開關機器才恢復**。

- 平台：`1.20.1/forge`（GTO 整合包專用，不抽 common）
- modid `gto_elevator_fix`、package `com.gtoelevatorfix`、log 前綴 `[elevatorfix]`
- 驗證對象：GTO `0.5.6-beta`（內含 gtocore `26.7.5-alpha`、JiJ 的 gtceu `26.7.3`）

## 症狀

算力供應充足、相關區塊也保持載入，但**重進遊戲之後**：

- 運算中心／算力監視器上原本被太空電梯占用的算力歸零；
- 太空電梯與底下所有模組（組裝／資源採集／工程數據）全部停擺；
- 放著多久都不會自己好；
- 把太空電梯**手動關機再開機**就立刻恢復，之後只要不重進遊戲就一直正常。

## 成因

太空電梯的常駐運轉是靠「配方永遠跑不完」實現的：`SpaceElevatorMachine.onWorking()` 在進度到 190 時
把進度設回 1。但那段回捲有守衛條件 `getRecipeLogic().getLastOriginRecipe() != null`，
而 GTCEU 的 `RecipeLogic.lastOriginRecipe` **是瞬態欄位、不寫存檔**。於是：

1. 重進遊戲後 `lastOriginRecipe` 是 null → 回捲失效 → 400 tick 的配方**真的跑完** → `onRecipeFinish()`。
2. 配方結束的那一 tick，`handleRecipeWorking()` 已經實扣了本機那份算力；緊接著重新搜配方時
   `matchTickRecipe()` 又以 simulate 要同樣的量，而運算中心給的是
   `getAdjustedMaxCWU() - allocatedCWUt`——算力沒有**兩倍餘量**就匹配失敗 → `setStatus(IDLE)`。
3. `IRecipeLogicMachine.keepSubscribing()` 預設回 `false`，`RecipeLogic.serverTick()` 走到尾巴就
   `unsubscribe()`，配方邏輯退訂伺服器 tick。
4. GTCEU 靠「配方處理器內容變動」的通知叫醒退訂的機器，但太空電梯的配方只吃 EU ＋ 算力，
   沒有任何會變化的物品／流體輸入（能源倉、算力倉都不發通知）→ **沒有東西叫得醒它**。
5. 手動關機再開機會走 `setWorkingEnabled()` → `setStatus()` → `updateTickSubscription()` 重新訂閱，
   同時重新搜配方並填回 `lastOriginRecipe`，所以就恢復了。

附帶：算力／能量瞬時不足觸發的 `RecipeLogic.interruptRecipe()` 也是直接 `unsubscribe()`，
理論上不只重進遊戲，算力供應抖動一次也可能讓太空電梯永久停擺——這個 mod 一併涵蓋。

守衛條件是上游 `0.5.6-pre2`（commit `0cae8fcd`）加進去的；整合包 `0.5.6-beta` 出貨的 jar 內
`onWorking()` 位元組碼確實有那個 `getLastOriginRecipe` 的 `ifnull`（`javap -c` 可重驗）。

## 這個 mod 做什麼

每 20 tick，對每一台已知的太空電梯控制器呼叫一次
`getRecipeLogic().updateTickSubscription()`。

- 那支方法自己會處理所有情況：已訂閱時是 no-op；玩家關機（SUSPEND）或結構未成型時會正確退訂。
  所以這裡**不做任何狀態判斷**，也不碰配方內容、產量、數值。
- 恢復延遲最多 1 秒。
- `SuperSpaceElevatorMachine`（通天之路）是 `SpaceElevatorMachine` 的子類，一併涵蓋。

### 怎麼找到太空電梯

`ChunkEvent.Load`（伺服端）時掃該區塊的 BlockEntity，另外延後 20 tick 再掃一次
（區塊剛載入時 BlockEntity 可能還沒從 NBT 實體化）。機器失效（`MetaMachine.isInValid()`）時自動移出追蹤。

> 這代表**新蓋好、且所在區塊還沒重新載入過**的太空電梯不在追蹤名單內。這不影響修復效果：
> 這個 bug 只會發生在「撐過一次重載」的機器上，而那種機器必定觸發過 `ChunkEvent.Load`。

### 為什麼不用 mixin

工作區既有紀錄顯示，往 `com.gtocore.*` 掛 mixin 有過兩次「進了 jar、config 正常載入、
零錯誤零警告、但實際沒生效」的前例，真因至今未明
（見 `../NL_gto_waitfix/docs/UPSTREAM.md §1`）。這裡要動的只是公開 API 的一次方法呼叫，
用反射就夠，順便讓 build 不必綁整合包的 GTCEU 版本。

## 開關與參數

| 系統屬性 | 預設 | 說明 |
|---|---|---|
| `-Dgtoelevatorfix.enabled=false` | 啟用 | 整個關掉 |
| `-Dgtoelevatorfix.period=<tick>` | `20` | 保活間隔 |

## 怎麼確認有生效

看 `logs/latest.log` 抓 `[elevatorfix]`：

- 載入時：`[elevatorfix] 已載入：每 20 tick …`
- 找到電梯：`[elevatorfix] 納入保活：SpaceElevatorMachine @ BlockPos{...}（minecraft:overworld）`
- **真的救到時**：`[elevatorfix] 喚醒了一台睡死的太空電梯…` ← 這行出現，就等於實機證實了上面那條成因鏈
- 關服時：`[elevatorfix] 本場共喚醒睡死的太空電梯配方邏輯 N 次。`

若整合包改了 API 導致反射失敗，會印一行 `ERROR` 並**整場停用保活**，不會每 tick 噴例外。

## 上游

同樣的修法已經改在 fork 的 GTOCore 上（`fix/space-elevator-keep-recipe-logic-alive`，
基底 `origin/26.8`），改法是在 `SpaceElevatorMachine` 內加同樣週期的保活訂閱。
上游合併並隨整合包出貨後，這個 mod 就可以移除。

# CHANGELOG

版本沿革與**退步歸因**都放這裡；`README.md` 只寫當前行為。

- 退步時退**單一行為**，不做整包回退，並在此記下歸因（哪個行為、什麼實錄、為什麼）。
- 每個行為改動都要記它的**退路旗標**。

---

## 0.1.0 — 2026-08-19 — 太空電梯保活

**內容**

- `ElevatorKeepAlive`：每 20 tick 對每一台已知的太空電梯控制器呼叫
  `getRecipeLogic().updateTickSubscription()`，讓被 GTCEU 退訂 tick 的配方邏輯重新掛回去。
  不改配方、不改產量、不改任何數值。
- 追蹤名單由 `ChunkEvent.Load`（伺服端，另延後 20 tick 補掃一次）建立，
  機器 `isInValid()` 時自動移出。
- **純反射，零 mixin**：目標只有 `MetaMachineBlockEntity.metaMachine`、`MetaMachine.isInValid()`、
  `WorkableMultiblockMachine.getRecipeLogic()`、`RecipeLogic.updateTickSubscription()`
  四個公開成員，外加診斷用的 `RecipeLogic.subscription` / `TickableSubscription.stillSubscribed`。
- 旗標：`-Dgtoelevatorfix.enabled=false`（整個關閉）、`-Dgtoelevatorfix.period=<tick>`（預設 20）。

**為什麼做這個**

太空電梯的常駐運轉靠 `SpaceElevatorMachine.onWorking()` 的進度回捲讓配方永遠跑不完，
而回捲的守衛條件讀 `RecipeLogic.lastOriginRecipe`——那是瞬態欄位、不寫存檔。重進遊戲後回捲失效、
配方跑完、同一 tick 內重新匹配算力必然短缺（運算中心只給 `最大算力 - 已占用`），
機器進 IDLE 後被 `keepSubscribing()==false` 退訂，而太空電梯沒有任何會變化的物品／流體輸入
能把它叫醒 → 永久停擺，只能手動開關機器。完整推導見 `README.md`。

**驗證狀態**

- 上游 jar 側已重驗：GTO `0.5.6-beta` 出貨的 `gtocore` jar 內，`SpaceElevatorMachine.onWorking()`
  的位元組碼確實有 `getLastOriginRecipe` 的 `ifnull`（`javap -c`）。
- 反射目標的名稱與簽章對整合包實際跑的 gtceu `26.7.3`（JiJ 在 gtocore jar 內）逐一 `javap` 核對過。
- **實機尚未驗證**。第一次真的救到時 log 會印
  `[elevatorfix] 喚醒了一台睡死的太空電梯…`——看到那行才算實證成因鏈。

# CHANGELOG

版本沿革與**退步歸因**都放這裡；`README.md` 只寫當前行為。

- 退步時退**單一行為**，不做整包回退，並在此記下歸因（哪個行為、什麼實錄、為什麼）。
- 每個行為改動都要記它的**退路旗標**。

---

## 0.2.0 — 2026-08-19 — 模組一起保活＋讀檔殘局清理

**歸因**：0.1.0 只保活控制器，實機結果是「電梯救回來了，但中間停了一下，模組跟著停、而且醒不了」。
`SpaceElevatorModuleMachine.handleTickRecipe()` 每 10 tick 檢查 `getSpaceElevatorTier() >= 8`，
而該值在 `controller.getRecipeLogic().isWorking()` 為假時是 0 → 模組立刻 `interruptRecipe()`
→ 同樣 `unsubscribe()`，之後沒有東西叫得醒它。也就是說 0.1.0 治了病源、沒治連鎖傷害。

**內容**

- **開場清帳**（治本）：控制器第一次被追蹤時，若處於「狀態 WORKING、但 `lastOriginRecipe` 是 null」
  ——只有剛讀檔才會出現的組合——就呼叫一次 `RecipeLogic.resetRecipeLogic()`，逼它立刻重搜配方。
  `lastOriginRecipe` 補回來後進度回捲恢復，讀檔後約 400 tick 那次「配方跑完 → 停一下」根本不會發生，
  模組自然不會被打斷。旗標：`-Dgtoelevatorfix.resetStaleRecipe=false`。
- **模組保活**（安全網）：把 `SpaceElevatorModuleMachine`（含資料模組、巨型模組等子類）
  一起納入保活。旗標：`-Dgtoelevatorfix.modules=false`。
- 保活間隔預設 20 → **5** tick，縮短任何一次意外停頓的恢復窗口。

**新的反射目標**（全部對整合包實跑的 gtceu `26.7.3` `javap` 核對過）

`RecipeLogic.getStatus()`、`RecipeLogic.getLastOriginRecipe()`、`RecipeLogic.resetRecipeLogic()`、
靜態欄位 `RecipeLogic.WORKING`，以及 `SpaceElevatorModuleMachine`。
模組的 `getRecipeLogic()` 沿用同一支 `Method`——
`SpaceElevatorModuleMachine → CustomParallelMultiblockMachine → ElectricMultiblockMachine
→ WorkableElectricMultiblockMachine → WorkableMultiblockMachine`，宣告類別一致。

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

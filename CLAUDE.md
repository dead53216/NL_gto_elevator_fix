# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 僅 `1.20.1/forge` 單平台（GTO 整合包專用），**不抽 common**；建置用 ModDevGradle legacyforge。
- modid `gto_elevator_fix`、package `com.gtoelevatorfix`、log 前綴 `[elevatorfix]`。
- **零 mixin、零 mod 依賴**：GTOCore／GTCEU 一律走反射。理由是工作區既有紀錄——往
  `com.gtocore.*` 掛 mixin 有過兩次「進 jar、config 正常載入、零錯誤零警告、但沒生效」的前例，
  真因未明（見 `../NL_gto_waitfix/docs/UPSTREAM.md §1`）。改這條之前先重讀那份筆記。
- **只碰公開 API，不改任何遊戲數值**。這個 mod 唯一被允許做的事是把
  `RecipeLogic.updateTickSubscription()` 叫回來；要動配方、產量、算力係數一律先開新版本討論。
- 反射目標的名稱／簽章要對**整合包實際跑的那份** gtceu（JiJ 在 gtocore jar 內）`javap` 核對，
  不是對 `other_mod/gto_repo` 的 HEAD——那份比整合包新。
- 反射任何一步失敗就整場停用並印一行 `ERROR`；**不准留半殘狀態每 tick 噴例外**。
- 行為改動一律附可單獨關閉的系統屬性；不做「一鍵全開」總開關。
- 上游對照：修法同時提在 fork 的 GTOCore（分支 `fix/space-elevator-keep-recipe-logic-alive`）。
  上游合併並隨整合包出貨後，這個 mod 應該移除而不是繼續疊功能。
- `VERSION` 只放**純數字版號**（`x.y.z`），工作目錄那份要保留 **CRLF**；
  `PREVIOUS_VERSION` 絕不可等於 `CURRENT_VERSION`（會把剛建好的 jar 刪掉、`dist` 變空）。

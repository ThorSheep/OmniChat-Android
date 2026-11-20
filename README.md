# OmniChat Android

Android App 使用 Kotlin、Compose 與系統語音辨識。顯示名稱為 OmniChat；套件 ID 保留 `com.example.meetingtranscript`，可覆蓋既有安裝並保留資料。

## 專案與下載

- [Android App 原始碼](https://github.com/ThorSheep/OmniChat-Android)
- [後端與 Web 倉庫](https://github.com/Evan1003/OmniChat)（目前為私人倉庫，需具備存取權）
- [測試版下載](https://github.com/ThorSheep/OmniChat-Android/releases)
- [自動建置進度](https://github.com/ThorSheep/OmniChat-Android/actions/workflows/release.yml)

本專案公開原始碼；`server/` 僅為保留的舊文字測試服務，正式後端請使用上方後端倉庫。

## 階段 2：伺服器與帳號

在底部「我的」進入使用者設定，新增伺服器名稱及 HTTPS API 網址。請使用 API URL，非 Web 前端 URL。討論室頁的伺服器名稱可前往設定。

新增彈窗可按「選擇預設伺服器」，選擇「OmniChat 測試伺服器」，帶入 `https://ours-dover-slots-convenience.trycloudflare.com/`；名稱空白時一併填入預設名稱，已輸入的名稱保留。名稱與網址仍可手動修改，按儲存才新增，不自動連線或登入。Quick Tunnel 網址可能變動，既有伺服器不會隨預設列表被覆寫。預設項目集中於 ServerAccountSection.kt 的 `serverPresets`，後續可在此增加選項。

- 點選伺服器卡片可選用、編輯及移除；選用後可登入、更新帳號資料與登出。
- 「我的」帳號卡片在名稱與 email 下方顯示身分標籤：學生／老師／管理員，依後端回傳的 `user.role` 顯示；未登入不顯示。後台變更角色後可按「更新帳號資料」重新取得，不提供 App 內角色修改或管理後台功能。
- 登入固定使用 `clientType: APP`；支援 `/auth/login`、`/auth/refresh`、`/auth/logout`、`GET /me`。
- access token 僅存記憶體；refresh token 由 Android Keystore AES-GCM 加密，依伺服器 UUID 保存，密碼不落地。
- App 重啟或選用伺服器時恢復登入。遇到 token 到期會序列化換發，避免同時重複使用 refresh token。
- 修改伺服器 URL 前須先登出；無法連線登出時保留登入資料以供重試，不自動將憑證移往其他網址。
- 移除伺服器會先嘗試遠端登出，成功後移除本機設定，不刪除本機逐字稿。

## 階段 3：遠端討論室

登入後在「討論室」按「新增討論室入口」，於彈窗輸入六碼代碼，只保存本機入口，不呼叫加入 API。首次加入前顯示代碼，加入成功後才保存後端正式名稱與 room ID。列表與最近使用紀錄依 server UUID 及 user ID 分開持久保存。

點列表卡片直接顯示加入確認；確認後才呼叫 `POST /rooms/join`，後端會自動退出原房間，成功後自動開啟獨立討論畫面。目前房間可直接「回到討論」，不重新加入；目前房間的「⋯」提供退出，其他已保存入口右側的刪除圖示直接開啟移除確認。移除不刪除伺服器或本機逐字稿；目前房間必須先退出。重複代碼不建立重複入口；代碼不存在或已關閉，在加入時提示。

底部分頁為「討論室、紀錄、我的」。討論室頁合併目前房間與已保存入口，最近使用排在前面，目前房間不在保存列表重複出現。獨立討論畫面可收起，底部分頁上方保留目前討論與收音狀態列；返回不呼叫 join，切換頁面不停止收音或退出。新增但尚未加入的入口不計為最近使用；退出成功回到列表，保留入口並移除目前討論狀態列。

App 恢復登入或切換帳號／伺服器時，以 `GET /me/room` 恢復目前房間；「更新狀態」可重新查詢。確認退出後呼叫 `POST /rooms/{id}/leave`，成功後清空房間。網路失敗保留畫面資料，並提示重新更新狀態；加入／退出逾時可能已在伺服器生效。

「舊測試討論室」入口仍可操作原本本機設定、歷史與 `/v1/transcripts` 收音流程。新伺服器登入不會改變舊逐字稿的上傳位置。

REST 授權請求只在 data 層取得 access token，遇到過期會換發並重試一次。切換登入身分會取消房間請求，過期結果以修訂序號攔截，不覆蓋新畫面。歷史及訊息查詢已在階段 5 接入，見下方說明。

階段 3 手動驗收：

1. 新增兩個開放討論室代碼，確認新增時後台成員沒有改變。重開 App 後兩筆入口都應保留；首次加入後才顯示正式名稱。
2. 測試小寫、前後空白、錯誤格式、不存在及已關閉代碼；錯誤應有繁體中文提示。
3. 點列表卡片確認加入，再加入另一室。後台應一次只有一個目前房間；加入失敗時保留原房間。測試重複新增代碼、確認取消、目前項目不可移除。
4. 完全關閉並重開 App，確認恢復同一房間；測試退出取消及確認退出，重開後應仍未加入。
5. 加入／退出時斷網，確認未假裝成功；恢復網路後按更新狀態確認實際結果。
6. 切換伺服器、登出及重新登入，不應看到前一帳號的房間；載入中切換時舊回應也不得覆蓋新房間。
7. 確認舊測試討論室的收音及歷史仍可操作。遠端討論室不應使用舊測試上傳端點。
8. 加入成功開啟獨立討論畫面；取消或失敗時不切換。「回到討論」不重送加入。退出後返回列表；新帳號且無紀錄時顯示新增入口引導。收音時收起、切到紀錄／我的再回討論，收音應持續。
9. 移除前需確認，移除最近使用項目後列表不應繼續顯示它。切換帳號／伺服器後列表與最近使用都必須隔離。

## 手動驗證

由使用者在 Android Studio Gradle Sync 後執行 `testDebugUnitTest` 與 Assemble app，再安裝 APK：

1. 檢查啟動器顯示 OmniChat 與藍色對話聲波圖示，Android 13+ 可測試系統主題圖示。
2. 新增兩個 HTTPS API 伺服器、切換及重啟 App，確認設定及登入狀態隔離。
3. 使用自己的測試帳號登入；確認顯示後端名稱與 email。測試錯誤密碼、無網路與無效 API URL。
4. 以「更新帳號資料」測試 GET /me；等待 access token 到期後再次更新，確認 refresh 正常。
5. 重啟 App 確認可恢復登入；另一支手機以同帳號 APP 登入後，更新帳號資料應提示重新登入。
6. 登出後重啟應保持登出；登出後才能修改伺服器 URL。移除需確認。
7. 檢查淺色／深色、討論室彈窗、收音通知與舊本機逐字稿仍正常。

## 階段 4：遠端收音與 Socket.IO 提交

加入後直接開啟獨立討論畫面；從列表或收起狀態列選「回到討論」也回到同一討論。連線與 `room:join` 成功，且連線目的地與畫面房間一致後，可按「開始收音」，通知列與背景行為沿用系統語音辨識服務。停止後保留本機原始逐字稿、提交狀態與待處理數量。

- 新流程使用 `io.socket:socket.io-client:2.1.1`（MIT），排除 Android 已內建的 org.json。Gradle Sync 需要下載此依賴及其遞移依賴。
- 連線使用 API URL 的 `/rt` namespace、websocket transport 與 handshake `auth.token`。應用程式層持有 sender，Activity／ViewModel 不持有 socket 或 token。
- 套件內建重連關閉，由 sender 以 1～30 秒退避重新取得憑證、核對原帳號及目前 REST 房間，再 connect、`room:join`。不以補送為由自動 REST 加入原房間。
- 每段最終文字先保存本機及新 outbox，接著送 `transcript:submit`。partial 只顯示。只提交 `clientMsgId`、`text`、`lang`、`clientTs`，不送音訊。
- ack 等待 5 秒；成功或 duplicate 均保存 messageId、serverTs、duplicate。原始段落 UUID 是 clientMsgId，逾時與重新啟動後重送仍使用同一 ID。
- outbox 永久綁定原 server UUID、user ID、room ID、API base URL。不同房間、帳號或改過 URL 的資料不會被重新指派；重新加入原房間且 URL 相符時才會補送。
- 失去 ack／斷網時保留待送資料；RATE_LIMITED 留在原連線退避。不可恢復的欄位錯誤及超過 2000 字的原文標示失敗並保留，不靜默截斷。
- 新 outbox 與舊 HTTP outbox 分開；遠端錄音不會呼叫 `/v1/transcripts`。已成功提交的 receipt 保留於本機，逐字稿面板依 message ID 合併本機與遠端顯示，待處理數量涵蓋該房間全部未完成段落。
- 退出、切換房間、切換伺服器或登出會先停止收音、完成目前已確認段落的本機保存，再暫停 sender。仍有待處理資料時須確認，文字保留在原目的地。取消操作可恢復連線，但不自動開麥克風。
- 保護事件 `room:closed`、`session:revoked` 會停止收音與提交。其他成員文字、presence、AI、`sync:since` 及完整遠端歷史已在階段 5 接入；各情境仍須分別驗收。
- App process 被終止時不自動開麥克風；使用者重新開啟、恢復登入及原房間後，sender 可讀取持久 outbox 繼續補送。背景網路持續性仍需實機驗收。

手動驗收（先執行 `testDebugUnitTest` 與 Assemble app）：

1. 加入後開啟討論畫面，等顯示已連線後開始收音。說一句話並停止，後台應只有一筆，側邊逐字稿應顯示自己的訊息且不重複。
2. 說話時確認 partial 不會提早出現在後台。確認手機麥克風與已配對藍牙耳機都可收音。
3. 收音期間切斷網路，確認最終文字保留；恢復後應自動重連與補送，後台不重複。系統辨識服務可能需要網路，未產生最終結果的語音不會被假裝保存為逐字稿。
4. 背景／鎖定螢幕持續收音，通知列停止應有效；回到 App 後狀態仍正確。
5. 仍有未送文字時退出、切換房間或登出，應先停麥並提示數量。確認後資料留在原房間；切換到新房間不能把舊資料送過去。
6. 重開 App／重新加入原房間確認補送；更新 Server Profile URL 後，舊 URL 的待送資料不可送到新網址。
7. 另一支手機登入同帳號或後台關閉房間，原手機應停止收音與提交，保留待送文字。
8. 回歸測試舊測試討論室、登入／refresh、列表切換、收起狀態列及主題；新舊兩種收音不能同時啟動。

階段 4 的正常收音與後台接收已由使用者確認。後端測試及手機安裝由使用者手動執行，Agent 未代為產生測試訊息或修改部署。

## 帳號功能與階段 5（程式碼已接入，待手動驗證）

- 使用者設定與登入視窗可建立帳號。註冊只送 email、displayName、password，後端固定建立學生帳號；成功後回到登入並帶入 email，不自動建立 session。
- 帳號卡片可修改顯示名稱與密碼。改名先停止收音、保存已確認文字，再 PATCH /me 並重建自己的 Socket；不自動開麥克風。改密碼使用 POST /me/password，目前裝置維持登入，輸入錯誤目前密碼不清除 session。確認密碼僅在本機檢查；密碼不用 rememberSaveable，也不持久保存。
- 側邊逐字稿接收自己、其他使用者、AI 與系統訊息，依 serverTs、ID 排序去重。AI 多語文字依房間語言設定排列，不下載或播放 AI 音訊。LazyColumn 按需繪製目前已同步的訊息；其他討論室紀錄由「紀錄」查詢。
- 在線名單來自 presence:update；加入時的成員快照另行顯示，不當成在線人數。斷線後清空在線與 AI 即時狀態。room:updated 更新房間資訊、AI 名稱及語言設定。
- 本機原始辨識、outbox 與討論訊息快取分開保存。ack 以 clientMsgId 對應 messageId，與遠端訊息合併後移除重複的本機顯示；不以內容相同推測同一則訊息。
- 快取與完整同步位置綁定 server UUID、user ID、room ID、原始 API URL。重連先保留完整同步位置，再合併 room:join 的 recentMessages，並執行 sync:since。truncated、位置 NOT_FOUND 或首次連線無位置時，用 REST 從最舊一頁讀完全部 nextCursor；完成前不前移同步位置。失敗保留快取及舊進度，退避重試；補齊後才重送 outbox。
- 底部「紀錄」可查看可讀取的討論室，討論中的紀錄入口直接查看目前房間，再返回全部討論室。歷史訊息由舊到新，「載入後續訊息」使用後端游標；歷史不呼叫 join／leave。既有「本機逐字稿」仍可讀。
- 未新增依賴、未修改後端參考庫／部署／Docker／外部服務，未更動語音辨識器，未清理舊測試資料。

### 帳號功能與階段 5 修改清單（前一批）

下表路徑以專案根目錄為基準；Kotlin 路徑前綴為 `app/src/main/java/com/example/meetingtranscript/`，測試前綴為 `app/src/test/java/com/example/meetingtranscript/`。

| 檔案 | 原因 |
| --- | --- |
| AGENTS.md | 記錄本批接入與待驗證狀態 |
| README.md | 更新使用說明、檔案清單及驗收步驟 |
| domain/Contracts.kt | 新增註冊、改名及改密碼介面 |
| domain/RemoteMeetingModels.kt | 完整討論狀態與依 ack ID 合併本機項目 |
| data/platform/RemoteAuthRepository.kt | 帳號操作及 session 保留規則 |
| data/platform/OmniChatAuthApi.kt | 串接既有帳號 REST 端點 |
| data/platform/RemoteMeetingRepository.kt | 歷史 REST 分頁與房間解碼 |
| data/platform/SocketIoMeetingClient.kt | 即時事件及 sync:since |
| data/platform/DiscussionMessageStore.kt（新增） | 隔離、持久訊息快取與完整同步位置 |
| data/platform/RemoteTranscriptSubmissionRepository.kt | 訊息事件、斷線補齊與提交順序 |
| data/platform/AccountServices.kt | 組裝快取與 REST 補齊來源 |
| presentation/AccountViewModel.kt | 帳號表單操作、停麥與錯誤提示 |
| presentation/MeetingViewModel.kt | 更新目前及已保存的房間資訊 |
| presentation/RemoteHistoryViewModel.kt（新增） | 隔離歷史查詢、游標與晚到回應 |
| ui/ServerAccountSection.kt | 註冊、改名及改密碼彈窗 |
| ui/RemoteMeetingScreen.kt | 多人訊息、成員、AI 與紀錄入口 |
| ui/RemoteHistoryScreen.kt（新增） | 遠端歷史及共用訊息卡片 |
| MainActivity.kt | 歷史導覽與登入／房間狀態連接 |
| 測試 data/platform/RemoteAuthRepositoryTest.kt | 註冊不登入、改名與改密碼 session 測試 |
| 測試 data/platform/RemoteSubmissionTest.kt | 同步 fallback、快取隔離、live 合併與 ack 去重測試 |
| 測試 presentation/AccountViewModelTest.kt | 補齊擴充後的帳號 fake 介面，保留伺服器選擇回歸測試 |
| 測試 presentation/RemoteHistoryViewModelTest.kt（新增） | 唯讀歷史分頁與舊回應隔離測試 |

### 帳號功能與階段 5 驗收順序

Agent 只進行原始碼與後端契約的唯讀核對；本批未執行 Gradle、lint、單元測試、APK 安裝或實機驗收。請在 Android Studio 依序執行 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`；成功前不視為建置通過。

1. 帳號：註冊新的測試帳號，確認成功後回登入且帶入 email；測試重複 email、名稱／密碼長度及確認密碼不一致。改名後卡片採用後端名稱，收音停止，重新開麥後後台發言名稱正確。改密碼測試錯誤目前密碼與成功流程；成功後目前手機維持登入，登出後新密碼可登入。
2. 多人與 AI：兩支手機用不同帳號加入同一房間，確認彼此文字、名字、時間及在線名單。測試 AI 思考／回覆／錯誤狀態、多語文字，以及後台更改房間名稱／AI 設定。確認自己的已送文字不重複；關閉房間及撤銷 session 均停止收音與提交。
3. 同步與歷史：離線期間由另一帳號產生訊息，再恢復連線；分別驗證少於及超過 200 則的缺口、同步途中有新訊息、同步途中再斷線與重啟 App。確認完整補齊、不重複，並在補齊後才送出 outbox。手機離線時辨識不產生最終文字，仍是辨識限制，不能視為補送驗收通過。
4. 歷史：瀏覽全部可讀房間及多頁訊息，確認由舊到新，且目前房間／麥克風不被改動。測試無權限房間、無網路、快速切換帳號／伺服器、不同 API URL 及本機舊逐字稿。
5. 回歸：通知列停止、背景／鎖定螢幕、手機麥克風、藍牙、中英混用、深淺主題、房間切換與登出待送確認。這些項目目前均未由 Agent 驗證。

## 本批 UI 調整：獨立討論與側邊逐字稿

本批程式碼已接入，尚未執行建置、lint 或實機驗收。沒有變更 API／Socket、資料儲存格式、後端、Docker、收音服務、Gradle 依賴，也未操作 Git 或建立 GitHub repository。

| 實際修改檔案 | 用途 |
| --- | --- |
| app/src/main/java/com/example/meetingtranscript/MainActivity.kt | 三個固定分頁、獨立討論頁、收起狀態列、返回與歷史導覽、本人 ID 傳遞 |
| app/src/main/java/com/example/meetingtranscript/ui/RemoteMeetingScreen.kt | 合併列表／最近使用、單次加入確認、目前房間退出選單與保存入口刪除圖示、精簡討論主畫面與固定收音控制 |
| app/src/main/java/com/example/meetingtranscript/ui/DiscussionTranscriptPanel.kt（新增） | 右側約 90% 面板、讀取位置、新訊息提示、即時文字與待送狀態、共用收音控制 |
| app/src/main/java/com/example/meetingtranscript/ui/RemoteHistoryScreen.kt | 即時／歷史共用本人藍色底與「你」、AI 輔助色與 AI 標籤、系統低視覺比重 |
| AGENTS.md | 更新已確認的導覽、面板、標示及驗證／GitHub 範圍 |
| README.md | 更新操作說明、本批清單與手動驗收 |

面板開關只影響顯示；收起或切換分頁不停止收音，不重新加入房間。同一房間在切換頁面期間保留面板閱讀位置與本機未讀提示；切換房間／身分或 Activity 重建時重設，不向伺服器提交已讀資訊。首次同步的既有紀錄不算新訊息。跟隨最新時自動捲動；向上閱讀後，新訊息與 partial 更新不強制捲動，可點「回到最新」。未新增這類可逆 UI 的單元測試；既有 domain／data 測試保持不變。

手動驗收請依序進行：

1. Android Studio 執行 `:app:assembleDebug` 與 `:app:lintDebug`，並以 `:app:testDebugUnitTest` 回歸既有測試；使用 Assemble 產出的新版 APK 安裝。以上本批均尚未執行，不能視為通過。
2. 確認底部分頁為「討論室／紀錄／我的」。新增入口仍為彈窗；點房間只需一次加入確認，成功後自動開啟討論畫面。目前房間不重複出現在保存列表；目前房間「⋯」可退出，其他保存入口的刪除圖示直接開啟移除確認，取消不改變房間。
3. 開始收音，開關右側逐字稿，測試關閉按鈕、點外側與系統返回鍵。面板約佔寬度 90%，底部可停止／開始收音，文字不貼狀態列；面板關閉不停止收音。
4. 兩個不同帳號同房發言，確認自己顯示藍色底與「你」，其他成員為中性色，AI 有不同底色與 AI 名稱。測試名稱相同但 ID 不同，不能誤標為本人；歷史紀錄也須正確標示。本機等待／正在送出與辨識中均標示「你」，ack 後不重複。
5. 關閉面板後收到新訊息，逐字稿按鈕應有數量提示。面板向上閱讀，另一帳號連續發言或本機產生 partial 時不跳到底部；點新訊息提示回到最新。關閉重開、收起再返回，同房間保留閱讀位置。
6. 系統返回鍵在討論畫面只收起；底部狀態列顯示房間與收音時間。切換紀錄／我的，再由狀態列返回，不新增 join，也不中斷收音。紀錄查詢、返回及舊本機逐字稿可正常使用。
7. 退出取消、確認退出、帶待送資料退出、切換房間、登出、房間關閉與 session 撤銷，均沿用停止／保留資料保護；退出成功回列表並移除狀態列。測試淺色／深色、大字體、橫向及背景通知列停止。

本批驗收通過後，才另行準備獨立 GitHub repository、忽略清單與首次提交內容。

### 保存入口刪除圖示調整（2026-11-21）

本次只修改 RemoteMeetingScreen.kt（將保存入口的單一操作選單改為刪除圖示）、AGENTS.md 與 README.md（同步操作規範及說明）。沿用原移除確認與本機刪除流程，目前房間退出操作不變。已核對原始碼；建置與手機驗證尚未執行，由使用者手動確認：點刪除只開啟移除確認、不觸發加入，取消保留入口，確認只移除該本機入口。

### 帳號身分顯示（2026-11-21）

本次只修改 ServerAccountSection.kt（在帳號卡片加入唯讀身分標籤）、AGENTS.md 與 README.md（同步顯示規範與說明）。使用既有角色 enum 與登入／GET /me 資料，不改 API、資料格式、權限或依賴。已核對三種 enum 對應及未登入不顯示的條件；未執行建置、lint、單元測試或實機驗證。由使用者手動 Assemble 後，以學生、老師、管理員帳號分別確認標籤，並檢查登出、切換伺服器、更新帳號資料及深淺色模式。

### 新增伺服器預設列表（2026-11-21）

本次只修改 ServerAccountSection.kt（集中預設列表及新增表單下拉選單）、AGENTS.md 與 README.md（同步規範與說明）。保留手動輸入、既有 HTTPS 驗證與儲存流程；不改 API、資料格式、依賴或既有伺服器資料。已核對選取僅更新表單及只在新增時顯示的條件；未連線測試預設端點，未執行建置、lint、單元測試或實機驗證。請手動 Assemble 後確認：選取帶入網址、空白名稱自動填入、自訂名稱不被覆寫、欄位仍可修改、取消不保存，以及編輯既有伺服器行為不變。

## GitHub 首次發布與後續版本

使用者於 2026-11-21 回報目前功能驗證完成，並核准公開獨立倉庫及 `v0.1.0` Pre-release。建置改由 GitHub Actions 執行，Agent 不上傳本機 APK，也不等待雲端建置完成；CI APK 的安裝驗收由使用者執行。

推送 `vMAJOR.MINOR.PATCH` 標籤會觸發 `.github/workflows/release.yml`，以 Java 17 執行 `:app:assembleDebug`，核對標籤與 `versionName` 相符，再使用 `docs/releases/<標籤>.md` 建立 Pre-release，附上 APK 與 SHA-256 校驗檔。此流程使用工作流程內建的 `GITHUB_TOKEN`，不需在程式碼放置個人 token，也不發布到 Google Play。失敗可從 Actions 手動重新執行該次工作流程；不以建置已觸發冒充成功完成。

若 GitHub 未替首次推送產生建置紀錄，可在 Actions 的「Build Android prerelease」選擇「Run workflow」，使用 main 分支的工作流程並輸入既有標籤（例如 `v0.1.0`）；流程會 checkout 該標籤的原始碼再建置，不移動或覆寫標籤。未來正常發布仍只需推送對應標籤。

目前為 CI Debug 簽章，與本機或後續建置的簽章可能不同；正式固定簽章另行規劃，暫不保證可覆蓋更新。App 的版本號仍由 `app/build.gradle.kts` 管理，未來發布前應更新 versionCode／versionName、準備對應版本說明並確認驗證結果，再推送標籤。

本次發布準備修改 `.gitignore`（排除參考庫、憑證及本機資料）、`AGENTS.md`、`README.md`，新增 `.github/workflows/release.yml` 與 `docs/releases/v0.1.0.md`。後端遠端 README 僅追加 App 倉庫連結；本機 `.backend-reference/OmniChat` 維持唯讀。不變更 App 功能、API 或部署；保留舊測試服務程式及範例設定，實際 `.env` 與資料不提交。自動測試、lint 及 CI APK 實機驗收未由 Agent 執行。

# 會議即時逐字稿 App：開發規範

## 1. 需求確認與變更授權

本節優先於其餘開發流程。收到任何功能、修正、重構或環境變更要求後，AI Agent 不得直接開始修改。

1. Agent 可先唯讀檢查相關程式碼、設定、後端契約與既有測試，以釐清影響範圍。
2. 實作前必須先向使用者說明：
   - 對需求的理解與預期結果。
   - 預計採用的實作方式。
   - 預計新增或修改的檔案。
   - 是否影響資料格式、API、Socket 事件、伺服器、Docker 或外部服務。
   - 預計執行的建置與驗證方式。
   - 本次明確不處理的內容。
3. 只有在使用者明確回覆「確認」、「開始實作」、「照此做」或同等意思後，才能修改檔案、安裝依賴或執行會改變專案及外部服務狀態的操作。
4. 若實作途中發現需要擴大範圍、增加檔案、改變 REST／Socket／資料契約或修改原先未列出的系統，必須停止，提出修訂方案並再次取得確認。
5. 不得以改善架構、安全性或完整性為由，順便重構、增加功能或修改與已確認需求無關的內容。
6. 已確認變更直接造成的編譯或測試錯誤，可以在原核准範圍內修正；若修正會改變功能行為、資料契約或擴大影響範圍，仍須重新確認。
7. 唯讀檢查、差異檢查及不改變外部狀態的診斷可在確認前執行；不得把診斷視為已取得實作授權。
8. 後端串接必須依第 14 節分階段進行。每一階段都要個別提案、確認、建置與驗證，不得一次實作所有階段；已明確核准的「帳號功能＋階段 5」合併範圍依該節辦理，不能推及其他階段。
9. 完成後須列出實際修改的檔案、各檔案修改原因、驗證結果及尚未驗證的項目。

## 2. 專案目標與目前狀態

本專案讓多位使用者在會議中，透過手機內建麥克風、藍牙耳機或其他系統支援的收音裝置，將各自的語音即時轉成文字。最終辨識文字會送到使用者登入並加入的遠端討論室，伺服器負責多人同步、歷史紀錄與 AI 討論功能。

目前 Android App 已具備或正在完善的功能：

- 使用者可主動開始與停止會議收音。
- 使用 Android 系統語音辨識顯示暫時結果與已確認結果，預設繁體中文並允許中英文混用。
- 支援內建麥克風、系統已配對的藍牙耳機及其他可用輸入裝置。
- 收音可透過 foreground service 在背景與鎖定螢幕期間繼續，通知列須清楚顯示麥克風使用狀態及停止入口。
- 逐字稿可保存於本機。
- 新流程已接入 Server Profile、登入恢復／登出、多討論室本機入口、單一遠端房間加入與切換，以及 Socket.IO 最終逐字稿提交；正常連線下的收音、文字送出及後台接收已由使用者確認。
- 舊本機討論室及 `/v1/transcripts` 只保留於「舊測試討論室」入口，不是新後端契約，待階段 6 另行確認清理。
- 補送、去重、背景／藍牙與停止保護須依實際測試分別驗收，不得由正常上傳成功推定全部通過。手機斷網時若系統辨識無法產生最終文字，須記錄為辨識限制，不能視為補送驗證通過。
- UI 採藍色主調、底部功能表，並支援淺色、深色與跟隨系統模式。

後端目前已完成：

- NestJS REST API、PostgreSQL、Redis 與 Socket.IO 即時服務。
- 帳號註冊、登入、token refresh、登出、單一手機 session 與角色／帳號狀態管理。
- 以六碼代碼加入討論室、恢復目前房間、退出、成員狀態與房間設定。
- Socket.IO `/rt` 的即時逐字稿、訊息、AI、presence、房間更新、關閉與 session 撤銷事件。
- 討論歷史查詢與匯出。
- 透過 Cloudflare Tunnel 提供 HTTPS／WSS 測試環境。

本階段新增的 Android 工作範圍：

- Server Profile、多伺服器隔離與可編輯的 API base URL。
- 正式使用者登入、token refresh、登出與 session 撤銷處理。
- 以六碼代碼加入後端討論室。
- Socket.IO 即時連線、最終逐字稿提交、多人訊息、成員狀態、AI 文字事件與斷線同步。
- 遠端歷史紀錄查詢，同時保持既有本機逐字稿可讀。
- 「註冊、修改顯示名稱、修改密碼＋階段 5」已完成本批程式碼接入；建置、單元測試與實機驗收尚待使用者執行。行為及範圍依第 6.3、9.4、10、14 節執行，不得把程式碼完成視為驗收通過。

目前仍不做：

- 自架串流語音辨識服務、完全分離的持續音訊擷取／辨識管線及音訊檔上傳。
- 自行實作藍牙配對或裝置驅動。
- 未經使用者主動操作的常駐或隱性錄音。
- Android App 內的管理員／老師後台功能。
- Android App 播放 AI 語音；後端 `ai:audio` 只提供 DISPLAY 用戶端。
- iOS App 與正式商店發版。已核准本 App 的獨立 Public GitHub 倉庫、標籤與 CI 測試版發布，規則見第 16 節。

## 3. 後端契約來源與參考規則

Android 串接的契約來源優先順序如下：

1. 後端 `packages/shared/src` 中的 schema、enum 與 Socket event 型別。
2. 後端 `docs/API_GUIDE.md`。
3. 後端實際 controller、service 與 E2E 測試。
4. 後端部署環境的唯讀 health／ready 檢查。

現有 Android `/v1/transcripts` 測試格式不得反過來要求新後端相容。若文件、schema 與部署行為不一致，先停止 App 實作，整理差異並交由使用者或後端團隊確認。

後端唯讀參考 clone 位於：

```text
.backend-reference/OmniChat
```

- Android 任務不得修改、提交、啟動或部署此參考 repository。
- 不得把參考 repository 的密鑰、`.env` 或帳密複製到 Android 專案。
- 更新、移除或重新 clone 前必須先說明原因並取得確認。
- Claude Artifact 與 Web 管理介面可作為操作理解的補充，但 API 串接以程式 schema、指南及測試為準。

## 4. 技術方向

- Android 優先採用 **Kotlin、Jetpack Compose、ViewModel、Coroutines / Flow**；平台相依與網路實作只放在 data/platform 層。
- REST 用戶端、Socket.IO 用戶端、序列化與安全 token 儲存必須位於可替換的 data/platform 實作後方。
- UI 不直接操作 `AudioRecord`、`AudioManager`、`SpeechRecognizer`、資料庫、HTTP、Socket 或 token。
- 第三方套件須有明確必要性、活躍維護度、Socket.IO v4 相容性及適用授權；新增前須在實作提案中列出套件、版本、用途與影響。
- 不把 App 名稱、套件識別、Cloudflare Quick Tunnel URL 或其他後端網址寫死為最終產品決策。
- Cloudflare Quick Tunnel URL 可能改變，Server Profile 的 base URL 必須可編輯並以 HTTPS 驗證。
- 目前核准的主要視覺方向是藍色調；顏色須由主題系統集中管理。
- 不得假設特定藍牙耳機、語音服務或裝置品牌的行為；相關能力必須偵測並提供可理解的降級處理。

## 5. 分層與模組邊界

依賴方向固定如下：

```text
ui -> presentation -> domain <- data/platform
```

- **ui**：Compose 元件、導覽、彈窗與可視化狀態，不包含錄音、辨識、HTTP、Socket 或 token 規則。
- **presentation**：ViewModel、UI state、使用者事件及錯誤訊息轉換。
- **domain**：資料模型、use case、repository/service 介面與純商業規則，不依賴 Android framework 或特定網路函式庫。
- **data/platform**：Android 音訊、語音辨識、本機儲存、安全憑證儲存、REST、Socket.IO 與其他平台實作。

至少維持下列抽象，呼叫端不可依賴 Android 或特定網路實作：

```kotlin
interface AudioCaptureService
interface SpeechToTextService
interface TranscriptRepository
interface ServerProfileRepository
interface AuthRepository
interface MeetingRepository
interface RealtimeMeetingClient
interface TranscriptSubmissionRepository
```

職責如下：

- `ServerProfileRepository`：管理不同伺服器的顯示名稱與 API base URL。
- `AuthRepository`：註冊、登入、refresh、登出、目前使用者、修改顯示名稱／密碼與 session 狀態。
- `MeetingRepository`：REST 加入、恢復、退出討論室及歷史查詢。
- `RealtimeMeetingClient`：Socket.IO 連線、房間訂閱、事件接收、ack 與斷線同步。
- `TranscriptSubmissionRepository`：最終文字提交、outbox、重送與 ack 狀態。
- `TranscriptRepository`：本機原始逐字稿 session 與 segment。
- 討論訊息快取與同步位置由獨立的 `DiscussionMessageStore` 職責管理，不混入原始辨識 session 或 outbox；合併、排序與同步位置規則必須可用純資料／fake 測試驗證。

`TranscriptionForegroundService` 不得直接組 REST／Socket JSON、管理 token 或自行決定重試規則；它只把最終辨識結果委派給 `TranscriptSubmissionRepository`。Socket 不可只由 ViewModel 持有，背景收音期間應由 application-scoped 的網路元件維持，foreground service 使同一 App process 持續存活。

若現有程式尚未符合此分層，不得在單一階段大規模搬移；須依第 14 節漸進式完成。不要為沒有第二種實作需求的 UI 元件增加不必要抽象。

## 6. 伺服器、登入與安全規則

### 6.1 Server Profile

- 伺服器與討論室是不同概念，目標關係為 `ServerProfile -> AuthSession -> RemoteMeetingRoom`。
- Server Profile 至少包含本機 UUID、顯示名稱及 HTTPS API base URL。
- 新增伺服器彈窗可從集中管理的預設列表選取，目前提供使用者指定的 OmniChat Cloudflare 測試 API 網址。選取只填入表單網址，名稱空白才填入預設名稱，欄位仍可編輯；按儲存才建立 Server Profile，不自動連線或登入。預設不是最終產品端點，不覆寫已保存的伺服器；編輯既有設定沿用原 HTTPS 驗證及登出限制。
- 不同 Server Profile 的 token、目前房間、outbox、訊息與歷史狀態必須隔離。
- 使用者可編輯 Quick Tunnel URL；變更 URL 不得把舊 outbox 靜默改送到新伺服器。
- 連線前可唯讀檢查 `/health` 與 `/ready`，但健康檢查失敗不得遺失本機資料。

### 6.2 登入與 token

- Android App 呼叫 `POST /auth/login` 時固定使用 `clientType: "APP"`。
- `deviceInfo` 只傳平台、一般裝置型號及 App 版本；不得傳 IMEI、MAC address、廣告 ID 或其他硬體敏感識別碼。
- access token 只存在記憶體，不寫入一般 SharedPreferences、log 或畫面。
- refresh token 必須使用 Android Keystore 支援的安全儲存，不得明文保存。
- 不保存使用者密碼；登入表單離開或完成後不得留下可回讀的密碼狀態。
- 每次 `/auth/refresh` 都必須原子性保存新的 refresh token，舊 token 立即視為失效。
- 多個請求同時遇到 `TOKEN_EXPIRED` 時只能執行一次 refresh，其他請求等待結果後再重送。
- `SESSION_REVOKED`、`TOKEN_INVALID`、`USER_DISABLED` 等不可恢復狀態必須清除本機 session、停止重連並回到登入畫面。
- 不得把管理員帳密、測試帳密、API key、Token 或服務秘密寫入 Kotlin、Compose、Gradle、README、測試、映像檔或 log。
- 管理員帳號僅供後台測試，不得成為 App 預設帳號或內建測試憑證。

### 6.3 註冊與個人資料

- 登入視窗提供「建立帳號」，使用既有 `POST /auth/register`，輸入顯示名稱、email、密碼與確認密碼。
- 註冊一律由後端建立 `STUDENT`；App 不提供選擇角色或提升權限。成功後返回登入、帶入 email，不自動呼叫 login 或建立額外 session。
- 顯示名稱去除前後空白後為 1～50 字；註冊及新密碼為 8～128 字。email 依後端 schema 正規化與驗證；後端仍是最終驗證來源。
- 確認密碼只在手機比對，不傳給後端。所有密碼欄位不落地、不使用可保存狀態，離開或完成操作後清除。
- 帳號卡片提供修改顯示名稱入口，使用 `PATCH /me`。成功後採用後端回傳的使用者資料更新卡片。
- 「我的」帳號卡片在名稱與 email 下方，以主題色標籤顯示後端 `user.role`：`STUDENT` 為學生、`TEACHER` 為老師、`ADMIN` 為管理員；未登入不顯示。沿用登入／恢復及「更新帳號資料」的回應，不新增查詢或角色修改入口，也不因顯示身分增加管理權限或後台功能。
- 後端改名不會自動刷新既有 Socket 的快取名稱。修改前先停止收音並完成本機保存，成功後重新連接自己的 Socket，刷新在線名稱；不得自動重新開啟麥克風，也不猜測覆寫已收到訊息的發言者名稱。
- 修改密碼使用 `POST /me/password`，傳目前密碼與新密碼；確認新密碼只在手機比對。成功為 `204`，保留目前登入，其他 session 由後端撤銷。
- 修改密碼時的 `400 INVALID_CREDENTIALS` 表示目前密碼錯誤，須顯示欄位提示，不可誤判成 token 失效而清除登入。
- 既有停止／待送資料確認流程必須保留；更改帳號資料不得導致待送文字遺失或改送到其他身分。

## 7. 討論室與 UI 操作流程

目標流程固定如下：

```text
選擇或新增伺服器
-> 登入
-> 輸入六碼討論室代碼
-> 顯示會前確認
-> 進入討論室並建立 Socket 連線
-> 使用者主動開始收音
```

- 遠端討論室由老師／管理員在後端建立；Android App 不建立遠端討論室。
- 使用者以 `POST /rooms/join` 及六碼代碼加入。代碼輸入需自動去空白及轉大寫，但仍以後端驗證結果為準。
- 會議室名稱使用後端 `room.name`，使用者名稱使用登入回應的 `user.displayName`，不得要求使用者為每個房間重複輸入。
- App 啟動並恢復登入後呼叫 `GET /me/room`，恢復使用者目前所在的討論室。
- 進入討論室前仍須有確認步驟；開始收音是進入後的另一個明確操作。
- 會議中至少顯示房間名稱、目前使用者、Socket 狀態、在線成員、收音狀態、AI 狀態、停止收音及退出入口。
- `room:closed` 時停止提交、結算本機段落並回到輸入代碼畫面。
- 退出時先停止收音，再取消 Socket 訂閱並呼叫 REST leave；各步驟失敗須顯示實際狀態，不得假裝已離開。
- 舊本機會議室資料在遷移完成前保持可讀，不得未經確認直接刪除或覆寫。
- 底部分頁固定為「討論室、紀錄、我的」。原首頁與列表合併，討論室頁顯示目前已加入、最近使用與保存入口；不顯示未取得的其他房間在線人數。
- 點選保存入口直接顯示加入確認，確認後加入成功即開啟獨立討論畫面；失敗、取消及等待待送確認時不自動切換。目前房間的「回到討論」僅切換畫面，不再次呼叫 join。
- 新增本機討論室入口使用彈窗；已保存且未加入的討論室右側直接提供刪除圖示，點擊後顯示移除本機入口確認，不再為單一移除操作開啟「⋯」選單。目前房間保留「⋯」退出選單，不在保存列表重複顯示，移除前仍須退出。
- 討論畫面顯示房間名稱、目前使用者、Socket、成員及 AI 狀態，底部固定收音裝置／時間及開始／停止控制。完整逐字稿從明確按鈕展開右側面板，主畫面不排列全部文字。
- 側邊逐字稿面板約佔手機寬度 90%，可按關閉、點外側或系統返回關閉。面板也保留收音控制，包含暫時辨識、遠端訊息與本機待送狀態。
- 面板保留同一房間的閱讀位置；閱讀舊內容不因新訊息或 partial 更新強制捲動，提供新訊息數量與「回到最新」。首次同步的既有紀錄不當成新訊息；此未讀狀態是本機 UI 提示，不向後端寫入已讀資訊。
- 收起討論後，底部功能表上方保留目前討論狀態列與返回入口；返回鍵在面板內先關閉面板，在討論畫面則收起。恢復登入後從列表及狀態列返回目前房間，不自動開麥克風。
- 收起、開關面板、切換列表／紀錄／設定，不得呼叫 leave 或停止收音。退出確認、先停麥及待送保護沿用既有流程；退出成功回到列表並移除目前討論狀態列，保留保存入口。
- 紀錄分頁提供遠端歷史與本機逐字稿入口；討論畫面的紀錄入口可直接查看該房間。歷史查詢不改變房間與收音狀態。
- 即時與歷史訊息共用標示：以目前登入 user ID 判定本人，使用主題藍色淡底及「你」；其他成員用中性色；AI 用主題輔助底色與 AI／名稱；系統降低視覺比重。不得依顯示名稱判定本人。
- AI 卡片依房間設定順序保留所有回應語言，顯示 thinking、speaking、idle、error 的對應狀態，但不播放 AI 語音。
- UI 批次只修改已確認的介面與文件，不改後端、收音、資料契約或依賴。使用者已回報本批驗證完成，並核准後續獨立 GitHub 倉庫與發布流程，依第 16 節辦理。

## 8. 收音與即時轉錄行為

### 8.1 使用者控制與隱私

- 收音只能由使用者在可見畫面中明確按下「開始」觸發，並須先取得麥克風權限。
- 收音中須提供清楚的畫面狀態、經過時間、目前輸入裝置及停止按鈕。
- 背景持續收音須使用正確類型的 Android foreground service 與持續通知，不可設計為隱藏錄音。
- 停止、權限撤回、服務被系統終止、裝置不可用、房間關閉或發生不可恢復錯誤時，應安全結束目前段落、更新狀態並通知使用者。
- 不得把原始音訊、逐字稿、token 或可識別資料寫入 log、crash report 或 analytics。

### 8.2 音訊裝置

- 只使用 Android 提供的音訊輸入與路由能力；只有系統已配對的藍牙裝置才視為可選裝置。
- 啟動時列出可用輸入裝置、顯示實際使用的裝置，並監聽插拔、連線與路由變更。
- 裝置中斷時，依產品規則切回手機麥克風或暫停，並在 UI 顯示實際結果，不得靜默遺失音訊。
- 不假定藍牙耳機的採樣率、聲道數或品質；應以系統回報的可用設定建立錄音器。

### 8.3 連續會議轉錄

- 「連續」代表使用者主動開始到停止之間持續提供轉錄體驗，不代表一次永不結束的語音辨識請求。
- 目前可保留 Android `SpeechRecognizer` 結束後重新啟動的實作；可能產生系統提示音或短暫中斷，視為已知限制。
- 暫時辨識結果只顯示於 UI，不得儲存為最終遠端訊息或送出 `transcript:submit`。
- 最終辨識結果形成不可變更的本機 `TranscriptSegment`，再交由 submission repository 提交。
- 若平台提供多個候選文字、語言標籤或信心分數，須保留於本機原始資料，不得在 App 端猜測或改寫語句。
- 後端目前只接受 `zh-TW`、`en`；無可靠語言標籤時以目前選定／預設的 `zh-TW` 提交，不得自行發明其他代碼。

## 9. 本機逐字稿與遠端訊息契約

本機原始辨識資料與遠端正式訊息是兩種不同模型，不得混為同一資料類別。

### 9.1 本機 TranscriptSession

- 每次收音建立獨立 `TranscriptSession`，保留 installation ID、開始／結束時間、原始 segment、alternatives、language 與 confidence。
- installation ID 不得使用 IMEI、MAC address 或廣告 ID。
- 既有本機逐字稿必須保持可讀；後端訊息不得覆寫本機原始辨識文字。

### 9.2 遠端 transcript:submit

只提交最終結果，契約固定為：

```json
{
  "clientMsgId": "segment UUID",
  "text": "最終辨識文字",
  "lang": "zh-TW",
  "clientTs": "ISO-8601 UTC"
}
```

- `TranscriptSegment.id` 可作為 `clientMsgId`，最長不得超過後端限制 64 字元。
- 相同段落重試必須使用相同 `clientMsgId`，不得重新產生 ID。
- `text` 去除前後空白後須為 1～2000 字；超長時應依已確認的產品規則切段，不可靜默截斷。
- 只有 Socket ack `ok: true` 後才能從 outbox 移除。
- ack 中的 `messageId`、`serverTs` 與 `duplicate` 必須保存或交由同步層處理，以支援排序與去重。
- 不得再向新後端提交舊格式的 `clientTranscriptId`、`meetingRoomId`、`roomDisplayName`、`serverUrl`、`userDisplayName` 或 `segments` 陣列。

### 9.3 遠端 Message

- 遠端訊息以後端 `MessagePublic` 為準，至少包含 message ID、room ID、sender、sender type、text、lang、clientTs、serverTs 與可選的 AI contents／trigger。
- 畫面排序以 `serverTs`、再以 `id` 決定同時間的穩定順序；手機時間只作 `clientTs` 參考。
- `message:new` 和提交 ack 到達順序不保證，必須以 message ID 合併與去重。
- 自己尚未取得伺服器確認的最終文字顯示為待送項目；ack 將 clientMsgId 對應到 messageId 後，必須與伺服器訊息合併，避免兩份相同發言。不得只用文字或時間猜測去重。
- `ai:message` 須轉為同一種遠端訊息模型；AI 不會再收到一份 `message:new`，不得因此漏顯示或在重連後重複顯示。

### 9.4 遠端歷史與訊息快取

- 訊息快取、同步位置與歷史畫面狀態依 server UUID、user ID、room ID 及原 API URL 隔離；切換身分後攔截舊請求結果。
- 使用 `GET /history/rooms` 列出有權讀取的討論室，使用 `GET /rooms/{id}/messages` 查詢訊息；顯示關閉／封存狀態，且服從後端權限。
- 歷史房間依後端規格新到舊，房間訊息舊到新。分頁使用原樣回傳的 `nextCursor`，不可自行解析、拼接或重建 cursor。
- 基本訊息瀏覽提供「載入後續訊息」；不能把向後續時間分頁標示成「載入更早訊息」。
- 本批僅做基本分頁瀏覽，不加入搜尋、進階篩選、CSV／JSON 匯出或刪除遠端資料。
- 遠端歷史與原有本機歷史入口並存；快取不代表完整紀錄，載入失敗或同步未完成時須顯示實際狀態。

## 10. Socket.IO 與斷線恢復規則

- 使用與 REST 相同的 API base URL、Socket.IO v4 相容 client、namespace `/rt`，正式環境使用 WSS。
- 握手 auth 使用最新 access token；token 不得出現在 URL query、log 或畫面。
- REST `POST /rooms/join` 成功後，才能連線並送出 Socket `room:join`。
- 一條 Socket 同時只訂閱一個討論室。

App 至少處理以下事件：

```text
App -> server: room:join, room:leave, transcript:submit, sync:since
server -> App: message:new, ai:status, ai:message, presence:update,
               room:updated, room:closed, session:revoked
```

- `ai:audio` 只供 DISPLAY，用戶端 App 不播放也不下載。
- `presence:update` 代表在線成員；`room:join` ack 的 members 代表已加入但不一定在線的人，兩者不得混用。
- `room:updated` 必須更新房間名稱、AI 名稱及相關設定；資料以完整後端物件為準，不自行產生預設假值取代實際設定。
- `RATE_LIMITED` 不得斷線或丟棄資料，應退避後以相同 `clientMsgId` 重送。
- server 主動撤銷 session 時停止手動重連並回到登入畫面。

一般網路中斷後的恢復順序固定為：

1. 確認 access token，必要時執行 single-flight refresh。
2. 重建 `/rt` Socket 連線。
3. 先保留斷線前已完成同步的位置，再送出 `room:join`；最近 50 則訊息可合併，但不能先用其最新 ID 覆蓋舊同步位置。
4. 使用保留的位置執行 `sync:since`，補回遺漏訊息；同步途中抵達的新事件也須依 ID 合併。
5. 以 message ID 合併、排序及去重，確定缺口補齊後，才持久更新已完整同步的位置；不能把最新收到的一則直接當成完整同步進度。
6. 重送 outbox 中尚未成功 ack 的訊息。

若 `sync:since` 回傳 `truncated: true`，或同步位置失效，改用 REST 訊息查詢分頁補齊。中途失敗保留舊同步位置及可用快取，提供重試，不得把部分結果標示為完整。

## 11. 後端、網路與外部服務規則

- API endpoint、DTO、Socket event、認證、重試或資料庫 schema 變更前，必須列出 Android 與後端兩側影響並取得確認。
- Android UI 不直接建立 HTTP request 或 Socket event；網路層不得持有 Compose UI 狀態。
- 所有 REST 錯誤以 `code` 判斷，`message` 僅供除錯；可取得時保留不含敏感資料的 `requestId` 供後端追蹤。
- 同步須具備離線累積、明確狀態、idempotency key、ack、退避重試及可觀察錯誤。
- 未經確認，不得修改後端 reference clone、另一個專案（包括 `E:\Projects\Simple_App`）、Caddyfile、Docker Compose、Cloudflare Tunnel、憑證、網路或既有容器，也不得重新啟動、停止或刪除服務。
- 健康檢查、讀取公開文件及其他唯讀請求可作診斷；不得使用管理員帳號執行寫入測試，除非使用者另行確認具體操作。

## 12. 品質、可讀性與測試

- 命名使用英文程式名稱；使用者可見文字與文件使用繁體中文。
- 優先採用小型、單一責任、可測試的函式與類別，避免大型 manager、隱式全域狀態與難以追蹤的 callback 鏈。
- 使用 sealed state 或明確錯誤型別描述認證、房間、Socket、錄音、提交與同步流程，不以多個布林旗標組合表示複雜狀態。
- 每個錯誤都須有不含敏感內容的技術紀錄與可行的使用者提示。
- 至少以 fake repository／client 測試 ViewModel；單元與 UI 測試不得依賴真實管理員帳密、真實麥克風或正式伺服器。

domain／data 行為測試至少逐步涵蓋：

- login、refresh token rotation、logout 與 single-flight refresh。
- 註冊重複 email、欄位限制、確認密碼不一致、成功後返回登入且不額外登入。
- 修改名稱後帳號卡片與 Socket 更新，修改密碼成功保留目前登入，錯誤目前密碼不誤登出，密碼欄位不保存。
- `SESSION_REVOKED`、無效 token、停用帳號及安全清除 session。
- 正確、錯誤、已關閉的房間代碼及目前房間恢復。
- `room:join`、presence、AI、room updated／closed 事件。
- 最終辨識轉提交、ack timeout、相同 `clientMsgId` 重送與 duplicate。
- Socket 重連、`sync:since`、truncated fallback、訊息排序與去重。
- ack／message:new 的兩種到達順序、AI 多語言事件、presence 與房間設定更新。
- 斷線缺口超過 200 則、同步中收到新事件、同步失敗後重試、REST cursor 分頁與查看歷史不改變成員關係。
- 不同 Server Profile 的 token、房間、outbox 與歷史資料隔離。
- App 背景／鎖定螢幕期間的收音、Socket 與 outbox 行為。
- 既有本機匿名逐字稿在登入功能加入後仍可讀。

實機驗收仍涵蓋內建麥克風、至少一款藍牙耳機、裝置切換、中斷、拒絕權限、背景／鎖定螢幕、中英混用與長時間會議。

## 13. 修改範圍、建置與人工驗證

1. 嚴格遵循第 1 節的先提案、確認後實作流程。
2. 一次需求只修改完成該階段所必需的檔案；提案列出的檔案視為預計範圍。
3. 修改前先閱讀受影響層、後端契約與既有測試，避免跨層耦合。
4. 新功能原則上先補齊 domain／data 行為與 fake 測試再接 UI；若現有架構不允許，須在提案中說明最小可行方式。
5. 不得因「架構更完整」自行大規模搬移程式、重新命名、格式化無關檔案、提前實作下一階段或撤回既有功能。
6. 覆寫 Android framework callback 時，須以目前 compile SDK 的實際宣告為準，完全對齊方法名稱、參數數量、可空性與回傳型別。
7. 對任何可空型別，每個存取點都須個別完成 null-safety 檢查；不可依賴錯誤的短路或智慧轉型假設。
8. 功能與契約變更時更新相關文件；本規範與最新明確產品決策衝突時，先修訂本檔並取得確認。
9. 不提交 API key、服務帳密、token、真實逐字稿、錄音檔、敏感裝置識別或個人資料。
10. 完成後列出實際修改檔案；若與確認清單不同，須說明追加確認紀錄。

建置與操作規則：

- 每個階段完成後執行相關單元測試及 Android build／lint；無法執行時記錄原因與未驗證範圍。
- Kotlin、Android framework callback、權限、service、manifest、REST／Socket DTO、token 或資料驗證變更後，`./gradlew :app:assembleDebug` 是最低必要驗證。
- 不得在沒有成功輸出的情況下宣稱建置或測試成功。
- 同一指令因相同原因失敗兩次後停止嘗試，說明失敗原因並提供完整指令，由使用者手動執行。
- 需要使用者執行 Docker、DNS、網路、憑證、管理員權限或外部服務指令時，一次只提供一個步驟並等待結果。
- 使用者表示要手動建置或驗證時，Agent 不得自行安裝 App、操作手機或擴大測試。
- 手動驗證結果須如實記錄，不能把未執行項目標記為通過。

## 14. 後端串接分階段流程

各階段原則上不得合併實作。唯一已核准的範圍調整為「註冊、修改顯示名稱、修改密碼＋階段 5」，依下述三部分完成；不包含階段 6。每階段或追加範圍開始前仍須提出目標、檔案、契約影響、測試及不處理項目，取得使用者確認後才開始。

### 階段 1：規範與 domain 契約

- 建立 Server Profile、認證、遠端房間、遠端訊息、Socket state 與 submission/outbox 的純 domain 契約。
- 先以 fake 實作測試狀態轉換。
- 不新增真實網路依賴，不改變目前可用的舊測試上傳流程。

### 階段 2：Server Profile、登入與 token

- 實作伺服器新增／選擇／編輯、login、refresh、logout、`GET /me` 與安全 token 儲存。
- 不實作加入房間與 Socket。

### 階段 3：加入、恢復與退出討論室

- 實作六碼代碼、`POST /rooms/join`、`GET /me/room`、REST leave 與會前確認 UI。
- 使用後端 room name 與 user display name。
- 不送出即時逐字稿。

### 階段 4：Socket.IO 與逐字稿提交

- 實作 `/rt`、`room:join`、`room:leave`、`transcript:submit`、ack 與新的 outbox。
- 將最終辨識結果接到 submission repository。
- 驗證成功後才停用新流程中的 `/v1/transcripts` 舊上傳；不得提前刪除舊本機資料。

### 階段 5：帳號功能、完整即時討論與歷史（已核准合併範圍）

- 第一部分：實作註冊、修改顯示名稱與修改密碼，依第 6.3 節執行。
- 第二部分：實作 `message:new`、多語言 AI、presence、room updated／closed、session revoked，整合完整討論畫面及自己的待送項目，依第 7、9.3 節執行。
- 第三部分：完成 `sync:since`、REST fallback、持久快取／同步位置與遠端歷史分頁，依第 9.4、10 節執行。
- 驗收順序為「帳號 → 多人與 AI → 同步與歷史」，每部分如實記錄建置、測試、實機結果與未驗證範圍，不得把尚未執行的測試當成已通過。
- 沿用既有後端 API，不新增套件、不修改後端部署、收音器或舊測試流程；若有必要擴張，須另行取得確認。
- 不包含 AI 語音播放、自架辨識、歷史搜尋／進階篩選／匯出或舊流程清理。
- 本批已依確認範圍接入 App 與 fake 測試；尚未由 Agent 建置、執行測試或操作手機。依 README 的帳號、多人／AI、同步／歷史順序等待使用者驗收，通過前不開始階段 6。

### 階段 6：舊測試流程清理

- 在新後端完整實機驗收通過後，另外提出舊 `/v1/transcripts`、舊本機會議室設定與測試 server 的遷移／移除方案。
- 刪除資料或相容碼前必須再次取得明確確認。

每一階段完成後必須等待使用者的手動驗證結果，才能提出下一階段實作；合併批次依上述三部分安排驗收。建置、單元測試執行與實機驗收目前由使用者在 Android Studio 手動完成，Agent 提供測試及步驟，不自行建置或安裝，除非使用者另行授權。

## 15. 目前階段完成定義

新後端 Android 串接完成的最低條件：

- 可新增、選擇及編輯不同 Server Profile，且不同伺服器的認證與資料彼此隔離。
- 可使用正式帳號以 `clientType: APP` 登入、安全換發 token、登出並處理 session 撤銷。
- 可建立學生帳號、修改顯示名稱與密碼；註冊成功返回登入，改名後刷新自己的連線，改密碼不誤登出目前裝置且不保存密碼。
- 可用六碼代碼加入後端討論室，重新啟動 App 後恢復目前房間。
- 討論頁顯示後端房間名稱、目前登入使用者、在線成員、Socket 與收音狀態，以及整場討論訊息和多語言 AI 回覆。
- 使用者主動開始後，可使用手機麥克風及至少一款已配對藍牙耳機收音，畫面顯示中英混用的暫時與最終文字。
- 只有最終文字透過 Socket.IO `transcript:submit` 送出；網路中斷、timeout、rate limit 或重送不會遺失或建立重複訊息。
- 可接收其他成員訊息、AI 文字與狀態、房間更新／關閉及 session 撤銷事件。
- 重連後能以 `sync:since` 或 REST fallback 補齊遺漏訊息並去重排序。
- 同步位置與快取能持久保存，重連缺口與新事件並行時不跳過訊息，自己的待送文字與伺服器回傳不重複顯示。
- 可查詢使用者有權讀取的遠端歷史紀錄，同時保持既有本機逐字稿可讀。
- 查看歷史不改變目前房間，分頁方向與後端一致，所有紀錄依伺服器／帳號／房間及原 URL 隔離。
- 背景收音具有可見通知及停止入口，既有收音、藍牙、深色模式與基本 UI 行為不被破壞。

自架語音辨識、音訊上傳、App 管理後台、AI 音訊播放、iOS 與正式發版仍屬後續功能。

## 16. GitHub 與測試版發布

- 使用者已驗證目前功能並核准 Public 倉庫 `ThorSheep/OmniChat-Android`、首次提交與 `v0.1.0` 標籤。
- 使用者指定只推送標籤，由 GitHub Actions 建置 Debug APK 並建立 Pre-release；不等待雲端建置完成，不上傳本機 APK。最終 APK 由使用者手動驗收，工作流程已觸發不等於建置成功。
- 工作流程使用 Java 17 與 `:app:assembleDebug`，標籤須與 versionName 相符，使用 `docs/releases/<標籤>.md` 並發布 APK 與 SHA-256 校驗檔。發布使用內建 GITHUB_TOKEN 的 contents: write，不建立或提交個人 token。
- 若首次推送未產生建置紀錄，可使用 workflow_dispatch 輸入既有標籤，從 main 的工作流程 checkout 該標籤建置；不移動標籤，不改 App 程式或上傳本機 APK。
- 公開提交前檢查完整檔案清單，排除後端參考 clone、實際 .env、local.properties、快取、建置產物、簽章私鑰與本機逐字稿／資料庫；只保留不含真實秘密的範例設定。
- Android README 連向 `Evan1003/OmniChat`。使用者另行核准僅修改該後端遠端 README，追加 Android 倉庫連結；不得藉此改動後端程式、API、部署或本機參考 clone。後端仍是私人倉庫，連結不代表可公開讀取其內容。
- Debug 簽章與正式固定發布簽章分開處理；目前不提交或上傳簽章金鑰，不承諾跨建置可直接覆蓋安裝，不新增商店發布流程。
- 發布準備範圍為 .gitignore、AGENTS.md、README.md、.github/workflows/release.yml、docs/releases/v0.1.0.md，以及 Git 初始化／commit／remote／tag／push 與上述後端 README 單一文件變更；不改 App 功能或自動安裝。

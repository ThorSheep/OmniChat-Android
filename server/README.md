# 測試 API 更新與驗收

此測試服務只接收文字，不接收音訊；目前 API 不使用金鑰驗證。

## 更新（由使用者手動操作）

2. 在 server 目錄執行 `docker compose up -d --build api`。既有 Caddy 不需修改。
4. 只有進入會議室後才能開始收音。新資料附有 meetingRoomId 與原始 serverUrl。

## 驗收

- `/health` 應回傳 200；可直接 POST/GET `/v1/transcripts`，GET 可加 `?meetingRoomId=<房間ID>`。
- 重複送出相同 session ID、segment ID 不應重複保存。
- 停網路產生文字後，資料進入本機 outbox；恢復網路後回到 App 或設定頁按「重試待上傳逐字稿」。每次新段落也會嘗試送出佇列。
- 刪除房間保留逐字稿；更改房間網址後，舊紀錄及待送資料仍保留原伺服器。
- 舊版無房間 ID 的紀錄不可直接歸入新房間。

目前佇列會在 App 回前景、新文字產生與手動重試時執行；尚無 App 完全關閉時的網路恢復排程。成員同步與串流 STT 尚未實作，系統辨識提示音仍沿用目前行為。

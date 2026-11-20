# OmniChat Android

OmniChat 是支援多人討論的 Android App，將各自的語音即時轉成文字，並同步顯示成員發言與 AI 回覆。

目前所有已開發功能均已由使用者驗證完成。

## 下載與相關專案

- [APK 測試版下載](https://github.com/ThorSheep/OmniChat-Android/releases)
- [Android 原始碼](https://github.com/ThorSheep/OmniChat-Android)
- [後端與 Web 倉庫](https://github.com/Evan1003/OmniChat)（需具備存取權）
- [GitHub Actions 建置進度](https://github.com/ThorSheep/OmniChat-Android/actions/workflows/release.yml)

## 主要功能

- 多伺服器設定，可選擇預設 API 網址或自行輸入。
- 帳號註冊、登入、改名、修改密碼及學生／老師／管理員身分顯示。
- 保存多個討論室入口，使用六碼代碼加入，一次參與一個討論室。
- 即時語音轉文字，支援手機麥克風、系統已配對的藍牙耳機及背景收音。
- 多人成員狀態、AI 文字回覆、斷線同步與待送文字處理。
- 側邊逐字稿面板、自己的發言標示及可收起的討論畫面。
- 遠端討論紀錄、本機逐字稿，以及淺色／深色／跟隨系統主題。

## 開始使用

1. 安裝 APK，在「我的」新增伺服器，選擇預設項目或填入 HTTPS API 網址。
2. 註冊或登入帳號。
3. 在「討論室」新增六碼代碼入口，點選並確認加入。
4. 按「開始收音」，透過「逐字稿」按鈕查看文字；可隨時停止收音。
5. 收起討論後可切換其他頁面，從底部狀態列返回；過往內容可在「紀錄」查詢。

收起畫面或關閉逐字稿不會停止收音；「退出討論室」會停止收音並離開。

## 開發與建置

- Android 8.0（API 26）以上。
- 使用 Kotlin、Jetpack Compose；建置需要 JDK 17 與 Android SDK 35。
- 以 Android Studio 開啟專案、完成 Gradle Sync，再執行 Assemble app；APK 位於 `app/build/outputs/apk/debug/`。
- Windows 也可執行：

```powershell
.\gradlew.bat :app:assembleDebug
```

開發規範見 [AGENTS.md](AGENTS.md)。`server/` 是保留的舊測試服務，正式後端請使用上方獨立倉庫。

## 使用限制

- 系統語音辨識可能依裝置需要網路，辨識重新啟動時可能有提示音。
- Cloudflare Quick Tunnel 測試網址可能變動，可在 App 編輯伺服器設定。
- 目前下載版本為 Debug APK，尚未設定固定發布簽章，不保證可直接覆蓋既有安裝。
- App 傳送辨識文字，不上傳原始音訊。

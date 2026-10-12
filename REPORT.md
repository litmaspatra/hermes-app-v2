# Hermes Mobile v2: report

Built from `hermes-app-main.zip`. Nothing here was run on a phone, and the full APK build (aapt2/d8/apksigner) could not
run in my sandbox (no access to Google's SDK servers). What WAS verified: Java compiles against android-36.jar
(`tools/java-check.sh`), 134 pure-Java unit cases pass (`tools/java-unit-tests.sh`), 88 web tests pass (`npm test`), TypeScript
type-checks, and `vite build` succeeds. The first real APK build happens in GitHub Actions (workflow `android-apk.yml`).

## Done, per phase
- P0 build/CI: `build.sh` creates the keystore folder, accepts ANDROID_SDK_ROOT, no hard-coded Homebrew JAVA_HOME; new workflow `.github/workflows/android-apk.yml`.
- P1 approvals: queue instead of one slot, always remembered (even with the app open), persistent notifications that `onResume` no longer cancels, per-approval PendingIntent codes, "timed out" notice, retry, no removal at countdown 0 (stale guard 5 min), keep-alive that stops Chromium freezing the page (and its WebSocket) while Hermes works or waits.
- P2 island: native overlay with the cat (9 moods), on-demand visibility (invisible drop strip when idle), approval buttons, drag-and-drop with prompt box and chips, background send through `window.hermesIsland`, status-chip pet text.
- P3 voice: `VoiceCommands` grammar (calls, messages only on the explicit phrase, music, timer/alarm, torch, volume, time, navigation; camera/reading blocked), `VoiceActions`, `VoiceController`, triggers: assistant corner-swipe panel (`HermesVoiceInteractionService/Session`), Quick Settings tile, optional triple-tap overlay (`TapTrigger`).
- P4 safe approvals: `ApprovalPolicy` (strict read-only allowlist, editable), History (500 entries, on device), risk chip in the app sheet, notification and island.
- P5 themes: Material 3 (default; light/dark/OLED/system + Material You), Neo-brutalism and Glass (light only), "Reduce effects", island frame follows the style; contrast test.
- Settings pages: Appearance (style), Island, Voice commands, Approvals (toggle, allowlist editor, test box, history).

## Deviations and simplifications (read these)
- Several numbers for one contact: if exactly one is a mobile it is used; otherwise unlocked says so and does nothing, locked says "Unlock to choose". The PRD's "ask which" dialog was not built.
- Voice recognition and the microphone: the foreground service is `specialUse`. Android 14+ may refuse background microphone use for the triple-tap and tile triggers; the assistant (corner swipe) path is the one Android clearly allows. Untested.
- The compact assistant panel depends on how Funtouch OS draws assistant windows; on any failure it falls back to opening the app in Live mode (the old behaviour).
- Triple tap anywhere uses ACTION_OUTSIDE on a 1 px overlay. It may not fire for other apps' touches on your Android version, and overlays are hidden on the lock screen. It is OFF by default.
- Dragging from another app needs that app to start a system-level drag. The island needs the app's activity alive to take the grant; otherwise it tries a direct read and says "Couldn't read that" if refused.
- Auto-approved commands that the plugin later reports as answered are matched by session, not by request id (the plugin's `clear` event has no id).
- `git diff`/`log` can run textconv drivers configured in a repo; only `--ext-diff` and `--textconv` are blocked.
- Messages while locked are OFF by default (a locked phone can be picked up by anyone); calls while locked are ON.
- The in-app sheet's answers are logged as "answered in the app" (the choice is not visible to the shell).
- The corner-swipe assistant now registers a VoiceInteractionService: after installing, re-select Hermes under Settings → Default apps → Digital assistant.
- Signing: an APK signed with a new key cannot update an install signed with another key. Add repo secret `KEYSTORE_B64` (your old key, alias `hermes`, password `hermesmobile`) or uninstall first.

## Device test checklist (tick on the iQOO Z9s)
- [ ] Approval stays visible until answered or timed out (20 background trials); two approvals in one chat answer the right request.
- [ ] A 30-minute background run keeps the socket alive while a job works.
- [ ] Drag an image from the gallery onto the island, type a request, get a reply notification with the app in the background.
- [ ] Triple tap anywhere shows the island and listens (unlocked); note whether it works on the lock screen.
- [ ] Corner swipe shows the compact panel without opening the app.
- [ ] "call <exact name>" places the call at once, unlocked and locked.
- [ ] Auto-approve answers `ls` and asks for `rm`.
- [ ] Each style renders every screen readably; Material follows dark mode.

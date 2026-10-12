# Hermes Mobile v2: report

Nothing here was run on a phone, and the full APK build (aapt2/d8/apksigner) could not run in my sandbox (no access to
Google's SDK servers). What WAS verified: Java compiles against android-36.jar (`tools/java-check.sh`), 134 pure-Java
unit cases pass (`tools/java-unit-tests.sh`), 88 web tests pass (`npm test`), TypeScript type-checks, `vite build`
succeeds, and the three styles were rendered in a headless browser. The real APK build happens in GitHub Actions
(`.github/workflows/android-apk.yml`).

## v2.1 fixes (after the first install)
- Two islands: the system island (the Live Update chip) covered the app's island. The chip is now off while the app's island is on; the setting "Also show in the system island" brings it back and moves the app's island below the status bar.
- Corner swipe said "I didn't catch that" at once: once Hermes is the digital assistant, Android makes `HermesRecognitionService` the phone's default speech recogniser, and that was a stub that returned an error. It now forwards to the real recogniser (Google's, or the first other one), and the listener asks for that recogniser directly. If the offline pack is missing it retries online. Errors now say what went wrong (microphone, internet, busy) instead of always "I didn't catch that", and the error code is written to History.
- More pets: cat, dog, fox, panda, bunny, robot (Settings → Island → Pet). The system chip uses the pet's emoji.
- Triple tap removed. New trigger: tap and hold on the LOCK SCREEN only (hold near the top centre; 400/600/900 ms). It is inactive while the phone is unlocked, so typing cannot trigger it. Android may hide overlays on the lock screen: if holding there does nothing on your phone, tell me.
- Glassmorphism redone: one fixed colour backdrop (the old version painted the gradient on several layers and left visible seams), frosted panels with a light edge, gradient accent buttons. Neo-brutalism: deeper pink accent so accent-coloured text is readable, hard shadows on the suggestion cards.

## Done, per phase
- P0 build/CI: `build.sh` creates the keystore folder, accepts ANDROID_SDK_ROOT, no hard-coded Homebrew JAVA_HOME; new workflow `.github/workflows/android-apk.yml`.
- P1 approvals: queue instead of one slot, always remembered (even with the app open), persistent notifications that `onResume` no longer cancels, per-approval PendingIntent codes, "timed out" notice, retry, no removal at countdown 0 (stale guard 5 min), keep-alive that stops Chromium freezing the page (and its WebSocket) while Hermes works or waits.
- P2 island: native overlay with the pet (9 moods, 6 species), on-demand visibility (invisible drop strip when idle), approval buttons, drag-and-drop with prompt box and chips, background send through `window.hermesIsland`.
- P3 voice: `VoiceCommands` grammar (calls, messages only on the explicit phrase, music, timer/alarm, torch, volume, time, navigation; camera/reading blocked), `VoiceActions`, `VoiceController`, triggers: assistant corner-swipe panel, Quick Settings tile, lock-screen tap-and-hold.
- P4 safe approvals: `ApprovalPolicy` (strict read-only allowlist, editable), History (500 entries, on device), risk chip in the app sheet, notification and island.
- P5 themes: Material 3 (default; light/dark/OLED/system + Material You), Neo-brutalism and Glass (light only), "Reduce effects", island frame follows the style; contrast test.
- Settings pages: Appearance (style), Island (pet, visibility, system island), Voice commands, Approvals (toggle, allowlist editor, test box, history).

## Deviations and simplifications
- Several numbers for one contact: if exactly one is a mobile it is used; otherwise unlocked says so and does nothing, locked says "Unlock to choose". No "ask which" dialog.
- The foreground service is `specialUse`; Android 14+ may refuse background microphone use for the tile and lock-screen triggers. The corner-swipe assistant path is the one Android clearly allows. Untested.
- The compact assistant panel depends on how Funtouch OS draws assistant windows; on failure it falls back to opening the app in Live mode (the old behaviour).
- Lock-screen hold: the island window asks Android to show over the lock screen (FLAG_SHOW_WHEN_LOCKED); phones that hide overlays there will not respond. A hold in the invisible zone is consumed, so a short tap there does nothing.
- Dragging from another app needs that app to start a system-level drag. The island needs the app's activity alive to take the grant; otherwise it tries a direct read and says "Couldn't read that" if refused.
- Auto-approved commands that the plugin later reports as answered are matched by session, not request id (the plugin's `clear` event has no id).
- `git diff`/`log` can run textconv drivers configured in a repo; only `--ext-diff` and `--textconv` are blocked.
- Messages while locked are OFF by default; calls while locked are ON.
- Answers given in the app's own sheet are logged as "answered in the app" (the choice is not visible to the shell).
- After installing, re-select Hermes under Settings → Default apps → Digital assistant.
- Signing: an APK signed with a new key cannot update an install signed with another key. Add repo secret `KEYSTORE_B64` (your old key, alias `hermes`, password `hermesmobile`) or uninstall first.

## Device test checklist
- [ ] Approval stays visible until answered or timed out (20 background trials); two approvals in one chat answer the right request.
- [ ] A 30-minute background run keeps the socket alive while a job works.
- [ ] Only one island is visible; the pet you picked animates.
- [ ] Drag an image from the gallery onto the island, type a request, get a reply notification with the app in the background.
- [ ] Tap and hold near the top centre of the lock screen shows the island and listens; it does nothing while unlocked or typing.
- [ ] Corner swipe shows the compact panel, listens, and runs "what time is it".
- [ ] "call <exact name>" places the call at once, unlocked and locked.
- [ ] Auto-approve answers `ls` and asks for `rm`.
- [ ] Each style renders every screen readably; Material follows dark mode.

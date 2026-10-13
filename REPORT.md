# Hermes Mobile v2: report

Not run on a phone, and the full APK build (aapt2/d8/apksigner) can't run in my sandbox. Verified here: Java compiles against
android-36.jar with all lint warnings on (none in our code), 134 pure-Java test cases, 88 web tests, TypeScript, `vite build`,
and every Settings page opened and closed in a headless browser in all three styles with a fake phone bridge.
The real APK build happens in GitHub Actions (`.github/workflows/android-apk.yml`).

## v2.2 (this round)
- **Glass Settings screens were see-through** (the chat showed through behind them). My CSS had made every full screen transparent. Fixed: full screens paint the same colour field, opaque.
- **Corner swipe: "Speech recognition failed (code 13)".** 13 = language unavailable. The listener now falls back step by step: your language offline, then online, then the phone's own language, then en-US. The corner swipe now listens on the floating pet (the assistant window stays alive but invisible so Android keeps giving it the microphone). Errors are shown on screen for a few seconds and written to History with their code.
- **System island vs floating pet.** Status and approvals now use the system island (Live Update chip and the notification's Allow/Deny buttons), switched on by default. The floating pet sits just BELOW the status bar, so nothing covers it or the drag-and-drop target. Android doesn't let any app receive a drop on the system island, so the drop target has to be the pet. Setting "Approval buttons on the floating pet" is off by default. The notification also carries a picture of the pet in its current mood.
- **Pets redone**: Blob (mint slime), Mika (an original chibi girl: lavender hair, star clip, teal eyes) and Cat. Expressions ease smoothly (eyes, brows, mouth, blush, arms, bounce, tilt) for sleeping, thinking, typing, startled approvals, curiosity, cheering, worry, listening. I did NOT draw Chika Fujiwara: she is a copyrighted character. Mika is original. These are drawn in code, untested on a screen; tell me what to change.
- **Removed: lock-screen tap-and-hold.** It could never work on stock Android (apps can't show or touch anything on the lock screen), so it was a function that did nothing. Triggers are now the corner swipe and the Quick Settings tile.
- **Quick Settings tile** did nothing the first time (it asked for listening before the service existed). Fixed, and it asks to unlock first on a locked phone.
- **Settings → Island** and **Voice commands** pages rewritten for the above.

## Audit: what was checked, what was found
Checked by script and by running the UI (not a line-by-line read of the ~25,000 lines):
- Bridge contract: every Java `@JavascriptInterface` method vs the web whitelist and the web's calls; every `window.hermes*` hook Java calls vs the page: all consistent.
- Java methods never referenced: none. javac `-Xlint:all`: no warnings in our sources. TypeScript unused locals: a few leftovers in untouched original files (Bots.tsx, Sheets.tsx), harmless; my own leftover removed.
- All 16 Settings rows × 3 styles: every page opens, back returns to Settings, no script errors.
- Found and fixed: transparent glass screens, dead lock-screen hold, tile race, native `reduce_effects` setting that was saved but never read, speech recogniser stub that broke the phone's default recogniser.
- Not covered: anything that needs a real phone (drag from another app, assist gesture, microphone from the background, notification actions).

## Earlier work (unchanged)
Approvals queue and keep-alive, read-only auto-approve with editable allowlist and History, risk chips, voice commands (calls, explicit messages, music, timer/alarm, torch, volume, time, navigation), three styles (Material 3 default with dark mode and Material You; Neo-brutalism and Glass light only), `build.sh` fixes and the CI workflow.

## Known limits
- Corner swipe needs Hermes chosen as the digital assistant (Settings → Default apps → Digital assistant). With the screen fully off or locked, apps can't read touches or buttons, so only the corner swipe (where your phone allows assistants on the lock screen) and the tile exist.
- Android 14+ may still limit background microphone use; the corner-swipe path is the one Android clearly allows.
- Several numbers for one contact: the mobile one is used if there's exactly one, otherwise it says so and does nothing.
- Signing: an APK signed with a new key can't update an install signed with another key. Add repo secret `KEYSTORE_B64` (old key, alias `hermes`, password `hermesmobile`) or uninstall first.

## Device checklist
- [ ] One island at the top (system), the pet just below it; drag an image from the gallery onto the pet and send a request.
- [ ] Approval: Allow/Deny buttons appear in the system island/notification and stay until answered.
- [ ] Corner swipe: the pet listens, "what time is it" answers; a failure shows its reason on screen.
- [ ] Quick Settings "Hermes listen" tile starts listening.
- [ ] Each pet (Blob, Mika, Cat) looks right in sleeping, working, approval, listening.
- [ ] Glass, Neo-brutalism and Material look right on Settings and chat.

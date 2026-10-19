# Hermes Mobile v2: report

## v2.7 (not run on a phone; Java not compiled against Android here, see "Verified")
- **Why FastBrain/Piper died with the app**: the app started them through a short-lived `proot-distro login`; when that login ends, proot kills everything it started. Now `hermes-plugin/hermes-mobile/services.py` runs inside Hermes' own long-lived process (loaded by the plugin): every 20 s it starts whatever is down (router, bridge 8765, Piper 8766, MiniLM). One supervisor at a time (file lock). If Hermes isn't running, the app starts the supervisor as the foreground process of its own Debian session. The app checks every 90 s.
- **Doctor** (Setup check, Settings → Voice, and automatically once per app start, at most every 6 h): checks and installs FastBrain, MiniLM model files + numpy/onnxruntime and switches FastBrain to MiniLM, Kokoro (natural neural voice, int8 model + voice pack, replaces Piper as the voice server; Piper voices stay as a fallback if installed), links FastBrain to Hindsight Lite, reports duplicate FastBrain folders, and tells whether Hindsight Lite can save by itself.
- **Pets**: install tries the phone, then Hermes' side fetches it (agentpets.dev, then petdex.dev manifest) and hands it over on the loopback. With no pet installed the app picks the first one that downloads by itself; the drawn blob shows until then.
- **Contacts**: duplicates of one person (same name, same number) are merged. Two or more different matches: it asks once ("Which one: A or B?", answer "first", "Sharma", "the last"), then remembers (alias, or the chosen number for two people of one name). Aliases are read from FastBrain's cache (fed from Hindsight Lite) and learned ones are written back to FastBrain and Hindsight; only aliases whose contact exists are used.
- Control port 8767 (same key). Redeploy `hermes-plugin/hermes-mobile` AND install the APK.

## Verified
Pure-Java tests (ContactMatcher 32 incl. new pickOption, VoiceCommands 60, ApprovalPolicy 87, Prosody 9), web typecheck and 88 web tests, services.py smoke-tested with a fake home (status, control port, auth). NOT done: Android compile, APK build, any phone test.

## v2.6
- **Approvals**: an in-app answer is now matched to the island's copy by request id, else by the command text (the page's and the plugin's session ids differ, which is why answered approvals kept coming back).
- **FastBrain/Piper bridges**: the scripts read the app's key from Termux's absolute path (as the plugin does; the home-folder default was wrong inside proot), and the app starts them itself (`scripts/start-app-bridges.sh` via Termux RUN_COMMAND) when a voice command finds them down. Piper finds voices in the usual folders (`~/piper-voices`, `~/.hermes/cache/piper-voices`, `~/FastBrain/piper-voices/*`).
- **Pets**: three download attempts, plus Import-a-file when the pet host refuses.

## v2.5
- **Approvals**: the plugin now says exactly WHICH request closed (it compares what it announced with what Hermes still has pending), stamps events with the time Hermes asked, and removes its own Termux fallback notification; the app closes by request id, remembers closed ids so a late event can't re-open one, and ignores approvals past their deadline. Redeploy `hermes-plugin/hermes-mobile` to Hermes as well as the APK.
- **System island**: strictly opt-in (no chronometer / Live Update style unless the toggle is on).
- **Music by voice**: FastBrain router first; if it doesn't take it, the request goes to Hermes (its pre-router: Gemini, Hindsight Lite, music helper); YouTube only if you say "youtube". Settings → Voice → Test FastBrain says what is wrong.
- **Pets**: AgentPets spritesheets (8x9 grid) downloaded on demand; pets keep inside the screen, flush to edges/corners when dropped near them, step away from a system drag; vsync animation; Material You colours; iOS-style frosted island.
- **Natural voice**: Piper server + phrase planner (breaths, pace by mood); in Settings → Voice; used for read-aloud and voice replies; falls back to the phone's voice.
- **Fixes**: no window blur on the glass island (it blurred the whole screen), glass sheets opaque enough to read, AgentPets-only pets with a browsable catalogue and thumbnails (CDN 401 fixed with a browser identity, trusted hosts only), island follows work happening in the chat page.
- **Icon**: pixelated and smaller.
Not run on a phone.

## v2.4: voice with FastBrain, conversations, one approval, pin the pet
Compiled here against android-36 (all Java), pure-Java tests (ApprovalPolicy 87, VoiceCommands 60, ContactMatcher 21), web typecheck, 88 web tests, `vite build`. NOT run on a phone.
- **Pet**: drag it anywhere, it stays where you drop it (Position → Pinned). "Snap to spots" restores the old snapping. Text on the island is multi-line (up to 5 lines) and shrinks when long; approvals show the description and the whole command (up to 8 lines, smaller when long), buttons on by default.
- **System island**: a Live Update chip cannot animate the pet (a static icon only), so it is OFF by default; only the app's island shows status/approvals. It stays available as an opt-in toggle.
- **Approvals once**: the in-app answer is now reported to the phone side at once, so nothing asks again when you leave the app. Notifications are posted only when the island can't show them (island off, buttons off, or phone locked).
- **Voice**: calls/messages match contacts in the app first (your aliases, whole name, first name, sound-alike: "butagorira" finds "Budagodira"), instantly; a weak guess asks "Did you mean X?" and "yes" saves it as an alias. The recogniser gets your contact names as hints and returns 5 guesses. Music, apps and the rest go to FastBrain via a key-protected loopback bridge (`scripts/fastbrain-router-tcp.py` in the FastBrain repo), skipping Hermes' 10-12 s overhead; what FastBrain can't do goes to Hermes.
- **Conversation**: only when you say "let's talk" / "baat karte hain", or after Hermes answered a question (Settings toggle): listen, answer, listen again; "stop" / "that's all" / two silences end it.
- Limits: continuing to listen in the background depends on Android letting the assistant keep the microphone; check on the phone.

Not run on a phone, and the full APK build (aapt2/d8/apksigner) can't run in my sandbox. Verified here: Java compiles against
android-36.jar with all lint warnings on (none in our code), 134 pure-Java test cases, 88 web tests, TypeScript, `vite build`,
and every Settings page opened and closed in a headless browser in all three styles with a fake phone bridge.
The real APK build happens in GitHub Actions (`.github/workflows/android-apk.yml`).

## v2.3: the pet moves around
- The pet sits in one of five spots, always just under the status bar or on the UPPER part of a side (never the lower part): top-left, top-centre, top-right, left side, right side.
- Default **Wander**: when it is just hanging around it walks (hops and leans the way it goes) to another spot every 20 to 40 seconds. It never moves while an approval, a drop prompt, voice listening or a drag is going on.
- **Drag the pet** to move it; it snaps to the nearest spot and stays there. Or pick a spot or Wander in Settings → Island → Position (the pet walks to it).
- At a side it stays flush to that edge even when its width changes (long status text, approval panel).
- Untested on a screen: tell me if the walking, the snapping or the spots look wrong.

## v2.2
- **Glass Settings screens were see-through** (the chat showed through behind them). My CSS had made every full screen transparent. Fixed: full screens paint the same colour field, opaque.
- **Corner swipe: "Speech recognition failed (code 13)".** 13 = language unavailable. The listener now falls back step by step: your language offline, then online, then the phone's own language, then en-US. The corner swipe listens on the floating pet (the assistant window stays alive but invisible so Android keeps giving it the microphone). Errors are shown on screen for a few seconds and written to History with their code.
- **System island vs floating pet.** Status and approvals use the system island (Live Update chip and the notification's Allow/Deny buttons), on by default. The floating pet sits just BELOW the status bar so nothing covers it or the drag-and-drop target. Android doesn't let any app receive a drop on the system island, so the drop target has to be the pet. "Approval buttons on the floating pet" is off by default. The notification carries a picture of the pet in its current mood.
- **Pets**: Blob (mint slime), Mika (an original chibi girl: lavender hair, star clip, teal eyes) and Cat. Expressions ease smoothly (eyes, brows, mouth, blush, arms, bounce, tilt). Chika Fujiwara is a copyrighted character, so Mika is original. Drawn in code, untested on a screen.
- **Removed: lock-screen tap-and-hold.** It can't work on stock Android (apps can't show or touch anything on the lock screen). Triggers are the corner swipe and the Quick Settings tile.
- **Quick Settings tile** did nothing the first time (it asked for listening before the service existed). Fixed; it asks to unlock first on a locked phone.
- The native `reduce_effects` setting was saved but never read: now the glass island honours it.

## Audit: what was checked, what was found
Checked by script and by running the UI (not a line-by-line read of the ~25,000 lines):
- Bridge contract: every Java `@JavascriptInterface` method vs the web whitelist and the web's calls; every `window.hermes*` hook Java calls vs the page: consistent.
- Java methods never referenced: none. javac `-Xlint:all`: no warnings in our sources. A few unused TypeScript locals remain in untouched original files (Bots.tsx, Sheets.tsx), harmless.
- All 16 Settings rows × 3 styles: every page opens, back returns to Settings, no script errors.
- Not covered: anything that needs a real phone (drag from another app, assist gesture, microphone from the background, notification actions).

## Earlier work (unchanged)
Approvals queue and keep-alive, read-only auto-approve with editable allowlist and History, risk chips, voice commands (calls, explicit messages, music, timer/alarm, torch, volume, time, navigation), three styles (Material 3 default with dark mode and Material You; Neo-brutalism and Glass light only), `build.sh` fixes and the CI workflow.

## Known limits
- Corner swipe needs Hermes chosen as the digital assistant (Settings → Default apps → Digital assistant). With the screen fully off or locked, apps can't read touches or buttons, so only the corner swipe (where your phone allows assistants on the lock screen) and the tile exist.
- Android 14+ may still limit background microphone use; the corner-swipe path is the one Android clearly allows.
- Several numbers for one contact: the mobile one is used if there's exactly one, otherwise it says so and does nothing.
- Signing: an APK signed with a new key can't update an install signed with another key. Add repo secret `KEYSTORE_B64` (old key, alias `hermes`, password `hermesmobile`) or uninstall first.

## Device checklist
- [ ] One island at the top (system), the pet just below it; it wanders between the spots, can be dragged, and stays put when pinned.
- [ ] Drag an image from the gallery onto the pet and send a request.
- [ ] Approval: Allow/Deny buttons appear in the system island/notification and stay until answered.
- [ ] Corner swipe: the pet listens, "what time is it" answers; a failure shows its reason on screen.
- [ ] Quick Settings "Hermes listen" tile starts listening.
- [ ] Each pet (Blob, Mika, Cat) looks right sleeping, working, approval, listening.
- [ ] Glass, Neo-brutalism and Material look right on Settings and chat.

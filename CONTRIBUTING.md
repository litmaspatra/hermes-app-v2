# Contributing

Pull requests and issues are welcome.

1. Fork the repo and create a branch from `main`.
2. Make your change. Keep it focused; one idea per PR.
3. Run the checks (CI runs the same on every PR):
   - `cd web && npm ci && npm test && npm run build`
   - `tools/web-e2e/run.sh` (headless browser against a mock Hermes)
   - `python3 tools/test_canvas.py`, `tools/test_media.py`, `tools/test_chat_search.py` if you touched the plugin
   - `tools/java-check.sh` if you touched `android/`
4. Open a pull request and fill in the template. Add a screenshot for UI changes.

## Project rules

- No native form controls (`<select>`, range/checkbox inputs) and no `confirm()`/`prompt()`/`alert()`; use `web/src/components/ui.tsx` and `web/src/dialog.tsx`.
- Every native bridge method takes the per-page-load key first and is listed in `web/src/secure-bridge.ts`.
- Keep `rehype-sanitize` in front of rendered Markdown.
- Colours come from CSS variables so the light theme works.
- No periodic loops or wake locks without a bound (battery).
- Don't commit keys, tokens, `*.keystore`, `.env` files, or screenshots of real chats.

By contributing you agree your work is released under the MIT license.

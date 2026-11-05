---
name: android-emulator-browser
description: Mirror, show, control, automate, and debug the Flutter Android app on an adb-connected emulator or device from the Codex in-app browser. Use for visible device proof, selector automation, logcat/crash diagnosis, and Android UI validation.
---

# Android Emulator Browser

## Overview

Use this skill to make an adb-connected Android screen visible and interactive inside Codex. It provides:

- Browser mirroring through `scripts/serve-android-emulator.mjs`.
- Codex in-app Browser handoff so the right-side panel opens to the mirror URL.
- Tap, swipe, text, hardware key, and selector-based input.
- Accessibility snapshots from `uiautomator`.
- Activity, focused window, logcat, and crash inspection.
- Structured proof bundles with screenshots, snapshots, logs, and a report.

Keep the skill adb-first and portable. Do not require root, `scrcpy`, global adb resets, or project source edits unless the user explicitly asks.

## Browser Workflow

1. Discover the target:

   ```bash
   adb devices
   emulator -list-avds
   ```

2. Build, install, and launch the Flutter Android app with the project's own commands first. See `references/android-workflows.md` only for the applicable Android/Flutter workflow.

3. Start the browser mirror in a long-running terminal:

   ```bash
   node <skill-root>/scripts/serve-android-emulator.mjs \
     --serial <serial> \
     --package <package.name>
   ```

   Useful flags:

   ```bash
   --port 0
   --interval 350
   --max-width 430
   --display-id <id>
   --adb /path/adb
   --proof-dir /tmp/android-browser-proof
   ```

4. Open and show the printed `http://127.0.0.1:<port>/` URL in the Codex in-app Browser. If `browser:control-in-app-browser` is available, this is a required skill step: claim an existing matching tab or open/navigate a tab to the mirror URL, then set Browser `visibility` to true so the user sees the Android mirror in the right-side panel. Read `references/codex-browser-workflow.md` before doing this handoff.

5. Verify a real device frame. A loaded page alone is not proof.

## Automation Workflow

Prefer selector automation when accessibility data is available:

```bash
curl -s http://127.0.0.1:<port>/snapshot
curl -s -X POST http://127.0.0.1:<port>/tap-selector \
  -H 'content-type: application/json' \
  --data '{"selector":{"text":"기록"}}'
curl -s -X POST http://127.0.0.1:<port>/wait-selector \
  -H 'content-type: application/json' \
  --data '{"selector":{"textContains":"완료"},"timeoutMs":10000}'
```

Supported selector fields include `elementRef`, `text`, `textContains`, `contentDescription`, `contentDescriptionContains`, `resourceId`, `className`, `role`, `enabled`, `focused`, `selected`, and `checked`. Read `references/automation-workflows.md` before relying on selector behavior.

## Debug Workflow

Use these endpoints while the browser server is running:

```bash
curl -s http://127.0.0.1:<port>/device
curl -s http://127.0.0.1:<port>/activity
curl -s 'http://127.0.0.1:<port>/logs?lines=500'
curl -s http://127.0.0.1:<port>/crash
```

Read `references/debug-workflows.md` for logcat scoping, crash diagnosis, and foreground activity checks.

## Proof Workflow

Create a structured proof bundle:

```bash
curl -s -X POST http://127.0.0.1:<port>/proof \
  -H 'content-type: application/json' \
  --data '{"packageName":"<package.name>"}'
```

The bundle includes `device.json`, `project.json`, `session.json`, `steps.jsonl`, `screenshots/`, `snapshots/`, `logs/`, and `report.md`. Read `references/proof-workflows.md` before claiming Android visual QA or debug reproduction is complete.

## Safety

- Always scope adb commands with `-s <serial>` once a device is known.
- If multiple devices are connected, stop and choose a serial instead of guessing.
- Never run `adb kill-server`, wipe data, delete an AVD, or stop unrelated emulators unless the user explicitly asks.
- Keep only the mirror terminal you started alive or clean it up.
- Use `--display-id` when a foldable or multi-display device returns unstable screenshots.
- Do not claim success from a local URL alone; capture visible proof and relevant logs.

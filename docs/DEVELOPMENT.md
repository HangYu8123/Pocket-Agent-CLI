# Pocket-CLI — development notes

Technical reference for people building or modifying the app. For usage, see the
[README](../README.md).

## How it works

```
┌──────────────── Android app (Kotlin) ────────────────┐
│  Launcher  →  Terminal (Termux terminal-view)         │
│  mic / extra keys / link opener  ─┐                   │
│  foreground service keeps sessions alive              │
└──────────────┬────────────────────┴───────────────────┘
               │ pty
   proot (Termux build, ptrace-based chroot, no root needed)
               │
   Ubuntu 24.04 rootfs in the app's private storage
     bash · git · python3 · node (LTS) · npm
     @anthropic-ai/claude-code · @openai/codex
```

* **Ubuntu rootfs**: `ubuntu-base-24.04` (arm64, or amd64 on x86_64 devices/emulators) is
  downloaded on first launch (~30 MB) and extracted by the app (`RootfsInstaller.kt`). A bootstrap
  script (`assets/bootstrap.sh`) then runs *inside* Ubuntu and installs git, Python, Node.js and
  both CLIs with `apt`/`npm`. It is idempotent; "Resume setup" simply re-runs it.
* **CLI verification**: `npm install` is not proof that a CLI works. The Claude Code package's
  postinstall places a native binary over `bin/claude.exe`, and when that fails (partial download,
  missing platform package) it only prints a warning and npm still exits 0; a failed "Update
  Claude Code and Codex" can also leave a dangling launcher. Both end in `exec: claude: not found`
  (exit 127). So the bootstrap's last step runs `claude --version` and `codex --version`,
  reinstalls a CLI that fails, and writes `.bootstrap_done` only when both start; the versions
  land in `/pocketagent/cli_versions`. The Claude Code and Codex launchers run
  `bootstrap.sh --ensure <cli>` first (`Mode` in `Env.kt`), which is a no-op when the launcher
  resolves to a real executable and otherwise reinstalls that CLI before `exec`. If the repair
  fails, the marker is removed so the home screen offers "Resume setup".
* **proot**: the Termux fork of proot (with Android patches) is shipped as `libproot.so` in
  `jniLibs/<abi>/` so Android extracts it to an executable location. `tools/fetch_native.py`
  regenerates those binaries from the Termux package repository and patches the `libtalloc.so.2`
  dependency name so it resolves from the same directory.
* **Login**: both CLIs try to open a browser with `xdg-open`. Inside Ubuntu that command is a shim
  that appends the URL to `/pocketagent/open_url`; `TerminalActivity` polls the file and fires
  `ACTION_VIEW`. The OAuth callback to `localhost` reaches the CLI because proot shares the phone's
  network stack.
* **Working folder** (`Workspace.kt`): a guest path stored in preferences. Ubuntu paths map to
  `files/ubuntu/…`; `/sdcard` and `/storage` paths map 1:1 to the host because proot bind-mounts
  them. `Proot.resolveCwd()` creates the folder on launch and falls back to `/root/projects`.
* **Terminal view** (`app/src/main/java/com/termux/view`): Termux's terminal-view v0.118.3 is
  vendored (GPL-3.0) rather than pulled from JitPack, because its `onCreateInputConnection`
  advertises either `TYPE_NULL` or a visible-password field, and keyboards (Gboard, Samsung,
  Sogou) refuse voice typing on both. The vendored copy advertises
  `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS | TYPE_TEXT_FLAG_MULTI_LINE` by default,
  which keeps the keyboard mic key enabled and leaves autocorrect off. "Raw keyboard input" in
  Settings restores upstream behaviour.
* **Folder browser** (`FolderBrowser.kt`): lists the working folder in-app, opens files through
  a `FileProvider`, and hands phone-storage folders to the system Files app via a
  `DocumentsContract` URI.
* **Dictation** (`VoiceInput.kt`, `WhisperEngine.kt`): system speech dialog → RecognitionService →
  offline whisper.cpp (`app/src/main/cpp`, built with the NDK, models from Hugging Face
  `ggerganov/whisper.cpp`, q5_1 quantised) → keyboard mic. Debug builds accept
  `am start … --es debug_transcribe file.wav` for automated tests.
* **Always-on speech** (`Speech.kt`): `SegmentRecorder` keeps `AudioRecord` open and cuts the
  stream into phrases by energy (RMS gate, 400 ms pre-roll, end after ~1 s of silence), so
  Whisper only runs on speech. `Listener` feeds those phrases to whisper.cpp on one worker
  thread and delivers text on the main thread; `Speaker` wraps `TextToSpeech` with per-request
  completion callbacks. `VoiceCommands` is the pure parser (unit-tested in `app/src/test`).
* **Wake word** (`WakeWordService.kt`): a `microphone` foreground service running `Listener`
  with the Tiny model and a "Hey Pat." prompt; a fuzzy match (edit distance ≤ 1 per short word)
  opens `MainActivity` with `EXTRA_VOICE`. It pauses whenever an app activity is started
  (`App` tracks the foreground state) so the activities can use the mic. Background activity
  starts need `SYSTEM_ALERT_WINDOW` on Android 10+; without it a full-screen-intent notification
  is posted instead. Android 15 refuses microphone services from `BOOT_COMPLETED`, so the boot
  receiver posts a "resume" notification and `MainActivity.onResume` restarts the service.
* **Voice commands** (`VoiceControl.kt`): a small state machine (command → folder name → which
  agent) driven by one-shot `Listener` runs and spoken prompts. New folders go under
  `Workspace.DEFAULT` and become the working folder before the agent launches.
* **Hands-free mode** (`HandsFree.kt`): continuous `Listener`; `VoiceCommands.splitSend` strips
  a trailing "send to Claude Code/Codex" and presses Enter. Reading uses terminal quiescence:
  `onTextChanged` fires many times a second (Codex redraws ~9×/s even with nothing new on
  screen), so the transcript is hashed at most every 250 ms and a 2.5 s settle timer restarts
  only when the content differs. When it fires, `extractNew()` diffs the transcript
  (`getTranscriptText`, which does not glue exactly-full rows together) against the last
  snapshot, drops box-drawing chrome, status hints, shell prompts, URLs (spoken as "link") and
  their wrapped continuation rows, lines already on screen and the echo of the sent message,
  then speaks the rest. A newly started session has its first screen read (capped at 600
  characters). Control words ("stop reading", "hands-free off") only apply when they are the
  whole phrase. The listener is muted while TTS plays. Verified on the emulator against the
  real Codex and Claude Code onboarding screens, with Google TTS reporting playback start and
  end (tag `PocketSpeaker`: `TTS_INIT`, `TTS_SPEAK rc=0`, `TTS_START`, `TTS_DONE`); the last
  diff inputs land in `Android/data/com.pocketagent/files/handsfree_{old,new,spoken}.txt` in
  debug builds.
* **i-have-adhd skill** (`bootstrap.sh --skills`, bundled in `assets/skills`): runs at the end
  of setup and of every update, and from Settings. Codex: `codex plugin marketplace add` +
  `codex plugin add`, rules block appended to `~/.codex/AGENTS.md` (the always-on route the
  skill documents). Claude Code: `claude plugin marketplace add` + `claude plugin install`, then
  `touch ~/.claude/.i-have-adhd-always` so the plugin's SessionStart hook injects the ruleset.
  Both CLIs must get `</dev/null`: with the terminal on stdin they block forever. If the plugin
  route fails (offline), the bundled copy goes to `~/.claude/skills` / `~/.codex/skills` and a
  SessionStart hook in `~/.claude/settings.json` replaces the plugin's. Output of the plugin
  commands lands in `/pocketagent/skills.log`.

## Build

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35, CMake 3.22, NDK 27; the NDK
compiles the bundled whisper.cpp).

```bash
python3 tools/fetch_native.py        # one-time: proot + libs from the Termux repo → jniLibs
tools/build_termux_jni.sh            # one-time: 16 KB-aligned libtermux.so for both ABIs
./gradlew assembleDebug              # → app/build/outputs/apk/debug/app-arm64-v8a-debug.apk (+ x86_64)
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The build produces one APK per ABI (`splits.abi`). Phones need the arm64-v8a one; the x86_64
one is for the emulator.

## Compatibility notes (modern phones)

* **16 KB page size** (some Android 15/16 devices): every bundled native binary is 16 KB aligned.
  The Termux JNI helper from JitPack is not, so `tools/build_termux_jni.sh` rebuilds it with the
  NDK and `packaging.jniLibs.pickFirsts` prefers that copy. whisper.cpp is linked with
  `-Wl,-z,max-page-size=16384`.
* **Executable location**: with `targetSdk 35` Android only allows `exec()` from the APK's native
  library directory, hence proot ships as `libproot.so`. Binaries *inside* the rootfs are started by
  proot's loader via `mmap(PROT_EXEC)`, which is still permitted for app data.
* **Foreground service**: declared as `specialUse` (no runtime limit) so long agent runs survive
  backgrounding on Android 14+. A partial wake lock is held while sessions run.
* **Codex sandbox**: Codex uses Landlock/seccomp on Linux, which proot cannot emulate. The installer
  writes `~/.codex/config.toml` with `sandbox_mode = "danger-full-access"`; the Ubuntu userland is
  already confined to the app's private storage.
* **Python packages**: `pip install` works directly (`break-system-packages` is preset), and
  `python` resolves to `python3`.

## Verifying dictation

* Whisper model + inference: `am start -n com.pocketagent/.TerminalActivity --es mode shell --es
  debug_transcribe /sdcard/…/jfk.wav` (debug builds) logs `DEBUG_TRANSCRIBE ok … text=…`.
* Whole dictation pipeline with speech standing in for the mic: `am start -n
  com.pocketagent/.TerminalActivity --es mode shell --es debug_dictate /sdcard/…/clip.wav`
  (debug builds, needs `adb root` on the emulator) transcribes the WAV, cleans it, shows the
  banner and inserts the text into the terminal exactly as the mic button does. Verified with
  macOS `say` clips: English sentences come back verbatim with the base model; Mandarin needs
  the Small model plus the language pinned to `zh`. The decoder gets a per-language coding
  vocabulary prompt (`WhisperEngine.promptFor`), which fixed "Python" in Chinese output.
* Recorder → Whisper → banner: every in-app dictation in a debug build writes
  `Android/data/com.pocketagent/files/last_dictation.wav` and logs a `DICTATION samples=… rms=…
  peak=…` line (tag `PocketWhisper`). A silent recording shows "Microphone captured silence".
* Keyboard voice typing: with the terminal focused, `dumpsys input_method` must show
  `inputType=0xa0001`; Gboard then shows its mic key and "Speak now". Emoji from Gboard's panel
  travel the same `commitText` path as dictated words.
* Emulator microphone on macOS: the first `-allow-host-audio` run hangs QEMU because macOS shows a
  microphone-permission prompt for the Terminal process (grant it once). Afterwards, run the
  emulator with a window and send `adb emu avd hostmicon`; `AudioRecord` then receives audio, but
  it arrives heavily muffled and Whisper cannot transcribe it, so real speech recognition through
  the microphone is only meaningful on a phone.

## Verifying voice control without a microphone

Debug builds take text in place of speech, so the flows run on the emulator:

* Home screen: `am start -n com.pocketagent/.MainActivity --es debug_say "create a new folder"`,
  then `--es debug_say "voice memo app"`, then `--es debug_say "claude"`; logcat tag
  `VoiceControl` shows each spoken prompt (`VOICE_SAY: …`), the folder appears under
  `files/ubuntu/root/projects`, the `workspace` preference changes and Claude Code launches.
* Hands-free: `am start -n com.pocketagent/.TerminalActivity --es mode shell --es debug_hear
  "echo hello from pocket, send to Claude Code."` types the command, presses Enter and, once the
  output settles, logs `HANDSFREE_SPEAK: hello from pocket` (tag `HandsFree`).
* `./gradlew testDebugUnitTest` covers the parser, the send-phrase split, wake-word matching and
  transcript diffing.

## Gotchas found the hard way

* whisper.cpp can emit byte sequences that are not valid UTF-8 (hallucinated tokens on silence
  or noise). `NewStringUTF()` on such bytes makes ART abort the whole process ("JNI DETECTED
  ERROR: input is not valid Modified UTF-8"). The JNI bridge therefore returns raw bytes and
  Kotlin decodes them with replacement. Found on the emulator after a silent 90 s recording.

* `TerminalView.setTypeface()` must be called after `setTextSize()` (it dereferences the renderer).
* `TerminalViewClient.onKeyUp(int, KeyEvent)` has the same signature as `Activity.onKeyUp`; the
  implementation must delegate to the Activity or the Back key stops working.
* libtermux passes the args array verbatim to `execvp()`, so `argv[0]` must be included.
* Objects created in Activity field initialisers must not touch the Context (no preferences).
* proot options used: `--kill-on-exit --link2symlink -0 --kernel-release=… -w <cwd>` plus binds for
  `/dev`, `/proc`, `/sys`, `/sdcard`, `/storage`, a fake `/proc/{stat,version,loadavg,uptime,vmstat}`
  and the bridge directory. `PROOT_LOADER`, `PROOT_TMP_DIR` and `LD_LIBRARY_PATH` point at the
  app's native-library directory.

## Testing on the emulator

An x86_64 API 35 Google APIs image runs everything natively (the app ships x86_64 proot and
whisper). Verified flow: install → rootfs download and extraction → bootstrap (about 8 minutes)
→ Ubuntu shell with Python → `xdg-open` bridge opens Chrome → Claude Code onboarding and login
link → Codex sign-in screen → working-folder creation on Ubuntu and phone storage → Back, session
persistence, mic button, offline Whisper transcription of `samples/jfk.wav`.

Handy adb helpers: `uiautomator dump` + `input tap` to drive the UI, `input text` (use `%s` for
spaces) to type into the terminal, `run-as com.pocketagent` to inspect app files.

## Layout

```
app/src/main/java/com/pocketagent/
  MainActivity.kt      launcher, install progress, working-folder picker
  TerminalActivity.kt  terminal UI, extra keys, dictation, link handling
  TerminalService.kt   foreground service owning the sessions
  RootfsInstaller.kt   download/extract/configure Ubuntu
  Proot.kt             proot command line + fake /proc files
  Workspace.kt         working-folder model
  VoiceInput.kt        dictation engines and fallbacks
  WhisperEngine.kt     whisper.cpp JNI wrapper, model download, recorder
app/src/main/cpp/                  whisper.cpp (vendored, GPU backends pruned) + JNI bridge
app/src/main/assets/bootstrap.sh   runs inside Ubuntu on first launch / update
app/src/main/jniLibs/<abi>/        proot, loader, talloc, android-shmem, libtermux
tools/fetch_native.py              regenerates jniLibs from the Termux repo
tools/build_termux_jni.sh          rebuilds libtermux.so with 16 KB alignment
```

## Licenses

proot (GPL-2.0), talloc (LGPL-3.0), libandroid-shmem (MIT), Termux terminal libraries (GPL-3.0),
whisper.cpp and ggml (MIT), Apache Commons Compress (Apache-2.0). This app's own code is MIT.
Claude Code and Codex are installed from npm at setup time under their own licenses and terms.

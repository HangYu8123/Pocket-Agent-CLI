#!/bin/bash
# Pocket-CLI bootstrap. Runs INSIDE the Ubuntu rootfs (under proot).
#   bootstrap.sh              first-launch setup (idempotent; "Resume setup" re-runs it)
#   bootstrap.sh --update     refresh Claude Code and Codex to the latest versions
#   bootstrap.sh --ensure X   pre-flight for launching CLI X (claude|codex): repairs it if
#                             it is missing or broken, exits 0 only when it actually runs
MODE="${1:-install}"
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export npm_config_fund=false npm_config_update_notifier=false npm_config_loglevel=error
NODE_FALLBACK="v24.20.0"
case "$(uname -m)" in x86_64) NODE_ARCH=x64 ;; *) NODE_ARCH=arm64 ;; esac
MARKER=/pocketagent/.bootstrap_done
VERSIONS=/pocketagent/cli_versions

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m!! %s\033[0m\n' "$*"; }

pkg_of() { case "$1" in claude) echo @anthropic-ai/claude-code ;; codex) echo @openai/codex ;; esac; }
title_of() { case "$1" in claude) echo "Claude Code" ;; codex) echo "Codex" ;; esac; }

# Fast, side-effect-free check: the launcher is on PATH and resolves to a real executable.
# Catches the two states behind "claude: not found" (exit 127): no launcher at all, or a
# launcher whose symlink chain ends in a file that no longer exists.
cli_present() {
  local p
  p=$(command -v "$1" 2>/dev/null) || return 1
  p=$(readlink -f "$p" 2>/dev/null) && [ -f "$p" ] && [ -x "$p" ]
}

# Explains why cli_present failed, for the log.
describe_cli() {
  local p
  if ! p=$(command -v "$1" 2>/dev/null); then
    warn "$1 is not on PATH (no /usr/local/bin/$1)."
  else
    warn "$1 launcher $p -> $(readlink -f "$p" 2>/dev/null || echo '?') is missing or not executable."
  fi
}

# Full check: the CLI starts and prints a version. Records it in $VERSIONS on success.
cli_runs() {
  local cli=$1 out st v
  cli_present "$cli" || { describe_cli "$cli"; return 1; }
  out=$(timeout 120 "$cli" --version 2>&1); st=$?
  v=$(printf '%s\n' "$out" | head -1)
  if [ "$st" -eq 0 ] && [ -n "$v" ]; then
    ok "$cli $v"
    grep -v "^$cli=" "$VERSIONS" 2>/dev/null > "$VERSIONS.tmp"; printf '%s=%s\n' "$cli" "$v" >> "$VERSIONS.tmp"; mv "$VERSIONS.tmp" "$VERSIONS"
    return 0
  fi
  warn "$cli is installed but does not start (exit $st): ${out:-no output}"
  return 1
}

# Clean reinstall of one CLI. npm can report success while its postinstall (which places
# the native binary) only printed a warning, so scripts run in the foreground here and the
# result is checked by cli_runs, not by npm's exit code.
repair_cli() {
  local cli=$1 pkg; pkg=$(pkg_of "$cli")
  say "Reinstalling $(title_of "$cli")"
  npm uninstall -g "$pkg" >/dev/null 2>&1 || true
  rm -f "/usr/local/bin/$cli"
  npm install -g --foreground-scripts "$pkg" || { fail "npm install $pkg failed."; return 1; }
  cli_runs "$cli"
}

# Verify both CLIs, repairing each broken one once. Returns non-zero if any still fails.
verify_clis() {
  local rc=0 cli
  for cli in claude codex; do
    cli_runs "$cli" && continue
    repair_cli "$cli" && continue
    fail "$(title_of "$cli") failed verification."
    rc=1
  done
  return "$rc"
}

if [ "$MODE" = "--ensure" ]; then
  cli=$2
  [ "$cli" = claude ] || [ "$cli" = codex ] || { fail "usage: bootstrap.sh --ensure claude|codex"; exit 2; }
  cli_present "$cli" && exit 0
  echo
  fail "$(title_of "$cli") is missing or broken. Repairing before launch…"
  describe_cli "$cli"
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    fail "Node.js is missing too, so the environment setup did not finish."
    rm -f "$MARKER"
    echo "    Go back to the home screen and tap \"Resume setup\"."
    exit 1
  fi
  if repair_cli "$cli"; then
    say "Repaired. Starting $(title_of "$cli")."
    exit 0
  fi
  rm -f "$MARKER"
  fail "Could not repair $(title_of "$cli"). Check the network, then go back and tap \"Resume setup\"."
  exit 1
fi

if [ "$MODE" = "--update" ]; then
  say "Updating Claude Code and Codex"
  if npm install -g @anthropic-ai/claude-code@latest @openai/codex@latest && verify_clis; then
    say "Done."
    exit 0
  fi
  fail "Update failed. Check your network and try again."
  # A failed update can leave a CLI half-installed; only keep the "ready" state if both run.
  if ! cli_present claude || ! cli_present codex; then
    rm -f "$MARKER"
    echo "    A CLI is now missing. Go back and tap \"Resume setup\" to reinstall it."
  fi
  exit 1
fi

echo
echo "  Pocket-CLI · Ubuntu $(. /etc/os-release; echo "$VERSION_ID") ($(uname -m)) setup"
echo "  This runs once and takes a few minutes. You can switch apps; the"
echo "  notification keeps it alive."
echo

if [ ! -x /usr/bin/git ] || [ ! -x /usr/bin/python3 ] || [ ! -x /usr/bin/curl ]; then
  say "Step 1/5 · Refreshing package lists"
  apt-get update -y || { fail "apt-get update failed. Check the network, then restart setup."; exit 1; }

  say "Step 2/5 · Installing base packages (git, python3, curl, nano…)"
  apt-get install -y --no-install-recommends \
      ca-certificates curl wget git nano less procps xz-utils unzip zip \
      openssh-client python3 python3-pip python3-venv python-is-python3 sudo jq ripgrep file \
    || { fail "apt-get install failed. Restart setup to retry."; exit 1; }
  apt-get clean
  rm -rf /var/lib/apt/lists/*
else
  say "Step 1-2/5 · Base packages already installed"
fi

if ! command -v node >/dev/null 2>&1; then
  say "Step 3/5 · Installing Node.js LTS"
  NODE_URL=$(curl -fsSL https://nodejs.org/dist/index.json 2>/dev/null \
    | python3 -c 'import json,sys
v=[x["version"] for x in json.load(sys.stdin) if x.get("lts")][0]
print(f"https://nodejs.org/dist/{v}/node-{v}-linux-'"$NODE_ARCH"'.tar.xz")' 2>/dev/null)
  [ -n "$NODE_URL" ] || NODE_URL="https://nodejs.org/dist/${NODE_FALLBACK}/node-${NODE_FALLBACK}-linux-${NODE_ARCH}.tar.xz"
  echo "    $NODE_URL"
  curl -fL --progress-bar "$NODE_URL" -o /tmp/node.tar.xz \
    && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
    && rm -f /tmp/node.tar.xz \
    || { fail "Node.js download failed. Restart setup to retry."; exit 1; }
  ok "node $(node --version), npm $(npm --version)"
else
  say "Step 3/5 · Node.js already installed ($(node --version))"
fi

if cli_present claude && cli_present codex; then
  say "Step 4/5 · Claude Code and Codex already installed"
else
  say "Step 4/5 · Installing Claude Code and Codex (npm)"
  npm install -g --foreground-scripts @anthropic-ai/claude-code @openai/codex \
    || { fail "npm install failed. Restart setup to retry."; exit 1; }
fi

say "Step 5/5 · Verifying Claude Code and Codex"
verify_clis || { rm -f "$MARKER"; fail "Setup is not complete. Restart setup to retry."; exit 1; }

mkdir -p /root/projects
touch "$MARKER"
say "Setup complete."
echo "    Go back and tap Claude Code or Codex. Each will ask you to log in the first time;"
echo "    the login page opens in your Android browser automatically."
echo

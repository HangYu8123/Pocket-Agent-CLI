#!/bin/bash
# Pocket-CLI bootstrap. Runs INSIDE the Ubuntu rootfs (under proot) on first launch,
# or with --update to refresh the CLIs. Safe to re-run: completed steps are skipped.
MODE="${1:-install}"
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export npm_config_fund=false npm_config_update_notifier=false npm_config_loglevel=error
NODE_FALLBACK="v24.20.0"
case "$(uname -m)" in x86_64) NODE_ARCH=x64 ;; *) NODE_ARCH=arm64 ;; esac

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m!! %s\033[0m\n' "$*"; }

if [ "$MODE" = "--update" ]; then
  say "Updating Claude Code and Codex"
  if npm install -g @anthropic-ai/claude-code@latest @openai/codex@latest; then
    ok "claude $(claude --version 2>/dev/null | head -1)"
    ok "codex  $(codex --version 2>/dev/null | head -1)"
    say "Done."
  else
    fail "npm update failed. Check your network and try again."
    exit 1
  fi
  exit 0
fi

echo
echo "  Pocket-CLI · Ubuntu $(. /etc/os-release; echo "$VERSION_ID") ($(uname -m)) setup"
echo "  This runs once and takes a few minutes. You can switch apps; the"
echo "  notification keeps it alive."
echo

if [ ! -x /usr/bin/git ] || [ ! -x /usr/bin/python3 ] || [ ! -x /usr/bin/curl ]; then
  say "Step 1/4 · Refreshing package lists"
  apt-get update -y || { fail "apt-get update failed. Check the network, then restart setup."; exit 1; }

  say "Step 2/4 · Installing base packages (git, python3, curl, nano…)"
  apt-get install -y --no-install-recommends \
      ca-certificates curl wget git nano less procps xz-utils unzip zip \
      openssh-client python3 python3-pip python3-venv python-is-python3 sudo jq ripgrep file \
    || { fail "apt-get install failed. Restart setup to retry."; exit 1; }
  apt-get clean
  rm -rf /var/lib/apt/lists/*
else
  say "Step 1-2/4 · Base packages already installed"
fi

if ! command -v node >/dev/null 2>&1; then
  say "Step 3/4 · Installing Node.js LTS"
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
  say "Step 3/4 · Node.js already installed ($(node --version))"
fi

say "Step 4/4 · Installing Claude Code and Codex (npm)"
npm install -g @anthropic-ai/claude-code @openai/codex \
  || { fail "npm install failed. Restart setup to retry."; exit 1; }
ok "claude $(claude --version 2>/dev/null | head -1)"
ok "codex  $(codex --version 2>/dev/null | head -1)"

mkdir -p /root/projects
touch /pocketagent/.bootstrap_done
say "Setup complete."
echo "    Go back and tap Claude Code or Codex. Each will ask you to log in the first time;"
echo "    the login page opens in your Android browser automatically."
echo

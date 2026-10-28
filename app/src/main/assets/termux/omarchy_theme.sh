#!/data/data/com.termux/files/usr/bin/bash
# Applies an Omarchy-style terminal theme inside the Arch Linux ARM proot container
# installed by install_archlinux_arm.sh. Run this from Termux (or via the launcher's
# "Apply Omarchy Theme" button). It logs into the Arch chroot and configures it from
# the outside - nothing here touches Termux's own shell.
#
# Omarchy (DHH's Arch + Hyprland distro) ships many swappable color themes; this
# mirrors its "Tokyo Night" palette (the one most commonly seen in Omarchy
# screenshots) as a zsh prompt + fastfetch/neofetch banner + ls colors, since a
# full Hyprland/Wayland session can't run without a display server on stock Android.

set -euo pipefail

if ! command -v proot-distro >/dev/null 2>&1; then
    echo "proot-distro not found - run install_archlinux_arm.sh first." >&2
    exit 1
fi

echo "==> Configuring Arch Linux ARM with an Omarchy-style (Tokyo Night) shell theme"

proot-distro login archlinux -- bash -s <<'ARCH_CHROOT_SCRIPT'
set -euo pipefail

echo "==> [arch] Updating pacman and installing shell + theme tooling"
pacman -Sy --noconfirm --needed archlinux-keyring
pacman -Syu --noconfirm --needed zsh git curl fastfetch

OMARCHY_DIR="/etc/omarchy"
mkdir -p "$OMARCHY_DIR"

cat > "$OMARCHY_DIR/tokyo-night.sh" <<'COLORS'
# Omarchy "Tokyo Night" palette - exported as env vars any prompt/tool can read.
export OMARCHY_BG="#1a1b26"
export OMARCHY_FG="#a9b1d6"
export OMARCHY_ACCENT="#7aa2f7"
export OMARCHY_RED="#f7768e"
export OMARCHY_GREEN="#9ece6a"
export OMARCHY_YELLOW="#e0af68"
export OMARCHY_MAGENTA="#ad8ee6"
COLORS

cat > /etc/fastfetch/config.jsonc <<'FASTFETCH' 2>/dev/null || true
{
  "logo": { "type": "small" },
  "display": { "separator": " -> " },
  "modules": ["title", "separator", "os", "kernel", "shell", "packages", "break"]
}
FASTFETCH

if [ ! -d "$HOME/.local/share/zsh-prompt" ]; then
  mkdir -p "$HOME/.local/share/zsh-prompt"
fi

cat > "$HOME/.zshrc" <<'ZSHRC'
source /etc/omarchy/tokyo-night.sh

# Omarchy-style minimal prompt: accent-blue arrow, dim path, no clutter.
autoload -Uz colors && colors
PROMPT='%F{63}->%f %F{245}%~%f %F{141}$%f '

export TERM="xterm-256color"
alias ls='ls --color=auto'
alias ll='ls -la --color=auto'
alias grep='grep --color=auto'

if command -v fastfetch >/dev/null 2>&1; then
  fastfetch
fi
ZSHRC

chsh -s /usr/bin/zsh root || true
echo "==> [arch] Omarchy-style zsh theme installed. Default shell set to zsh."
ARCH_CHROOT_SCRIPT

echo "==> Done. Next time: proot-distro login archlinux"

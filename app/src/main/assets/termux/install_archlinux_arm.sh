#!/data/data/com.termux/files/usr/bin/bash
# Installs Arch Linux ARM inside Termux using the official proot-distro project.
# Run from inside Termux (this script is dispatched there by Omarchy Launcher's
# "Install Arch Linux ARM" button via Termux's RUN_COMMAND Intent API), or paste
# it directly into a Termux session - it has no dependency on the launcher app.
#
# What this does:
#   1. Updates Termux's own package index and installs proot-distro.
#   2. Uses proot-distro to download and unpack the Arch Linux ARM rootfs
#      (archlinuxarm.org) for this device's CPU architecture, then runs it
#      under proot - no root access, no bootloader/kernel changes.
#
# proot-distro's "archlinux" alias resolves to the ARM build automatically on
# aarch64/armv7 devices, since Arch Linux ARM is architecture's ARM distribution.

set -euo pipefail

echo "==> Updating Termux package index"
pkg update -y

echo "==> Installing proot-distro"
pkg install -y proot-distro

if proot-distro list 2>/dev/null | grep -q '^archlinux\b.*installed'; then
    echo "==> Arch Linux ARM is already installed, skipping bootstrap"
else
    echo "==> Installing Arch Linux ARM via proot-distro (this downloads a rootfs, may take a while)"
    proot-distro install archlinux
fi

echo "==> Arch Linux ARM is ready."
echo "==> Log in any time with:  proot-distro login archlinux"
echo "==> Next: run omarchy_theme.sh (from the launcher's Terminal tab, or manually) to apply the Omarchy-style shell theme inside it."

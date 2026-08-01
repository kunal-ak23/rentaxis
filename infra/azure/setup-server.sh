#!/usr/bin/env bash
# =============================================================================
# setup-server.sh — Bootstrap a fresh Azure VM for RentAxis
#
# Usage:
#   ./infra/azure/setup-server.sh <env-file>
#
# What it does:
#   1. SSHs into the VM
#   2. Installs Docker + Docker Compose
#   3. Formats and mounts the data disk at /data
#   4. Creates directory structure
#
# Idempotent: safe to re-run.
# =============================================================================
set -euo pipefail

ENV_FILE="${1:?Usage: $0 <env-file>}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: Environment file '$ENV_FILE' not found."
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

# Point the CLI at the configured subscription so `az` calls target it.
# Blank AZURE_SUBSCRIPTION keeps the CLI's current default.
if [[ -n "${AZURE_SUBSCRIPTION:-}" ]]; then
  echo "==> Using Azure subscription: $AZURE_SUBSCRIPTION"
  az account set --subscription "$AZURE_SUBSCRIPTION"
fi

# Resolve public IP
PUBLIC_IP=$(az network public-ip show \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "${AZURE_VM_NAME}-pip" \
  --query "ipAddress" -o tsv)

SSH_TARGET="${AZURE_ADMIN_USER}@${PUBLIC_IP}"
SSH_OPTS="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"

echo "==> Setting up server: $SSH_TARGET"

# shellcheck disable=SC2087
ssh $SSH_OPTS "$SSH_TARGET" bash -s <<'REMOTE_SCRIPT'
set -euo pipefail

echo "==> Installing Docker..."
if ! command -v docker &>/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq ca-certificates curl gnupg lsb-release

  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg

  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
    sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

  sudo apt-get update -qq
  sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin

  sudo usermod -aG docker "$USER"
  echo "    Docker installed."
else
  echo "    Docker already installed."
fi

echo "==> Mounting data disk..."
DATA_DIR="/data"
DEVICE="/dev/sdc"

# Find the unformatted data disk (the 128GB one attached by Azure)
# Azure attaches data disks starting at /dev/sdc on Ubuntu
if [[ ! -d "$DATA_DIR" ]]; then
  # Wait for device to appear
  for i in {1..10}; do
    if [[ -b "$DEVICE" ]]; then break; fi
    echo "    Waiting for $DEVICE..."
    sleep 2
  done

  if [[ ! -b "$DEVICE" ]]; then
    echo "    ERROR: Data disk device $DEVICE not found."
    echo "    Available disks:"
    lsblk
    exit 1
  fi

  # Check if already formatted
  if ! sudo blkid "$DEVICE" | grep -q ext4; then
    echo "    Formatting $DEVICE as ext4..."
    sudo mkfs.ext4 -F "$DEVICE"
  fi

  sudo mkdir -p "$DATA_DIR"
  sudo mount "$DEVICE" "$DATA_DIR"

  # Add to fstab for persistence across reboots
  UUID=$(sudo blkid -s UUID -o value "$DEVICE")
  if ! grep -q "$UUID" /etc/fstab; then
    echo "UUID=$UUID $DATA_DIR ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab
  fi

  echo "    Data disk mounted at $DATA_DIR."
else
  echo "    $DATA_DIR already exists."
  # Ensure it's mounted
  if ! mountpoint -q "$DATA_DIR"; then
    sudo mount -a
  fi
fi

echo "==> Creating directory structure..."
sudo mkdir -p /data/postgres
sudo mkdir -p /opt/rentaxis
sudo chown -R "$USER:$USER" /opt/rentaxis
# PostgreSQL container runs as uid 999
sudo chown -R 999:999 /data/postgres

echo "==> Enabling Docker to start on boot..."
sudo systemctl enable docker
sudo systemctl start docker

echo ""
echo "==> Server setup complete!"
echo "    Docker version: $(docker --version)"
echo "    Data disk mounted at /data"
echo "    App directory: /opt/rentaxis"
REMOTE_SCRIPT

echo ""
echo "==> Server setup complete. Next: ./infra/deploy.sh $ENV_FILE"

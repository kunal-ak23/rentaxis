#!/usr/bin/env bash
# =============================================================================
# status.sh — Check the status of a RentAxis deployment
#
# Usage: ./infra/azure/status.sh <env-file>
# =============================================================================
set -euo pipefail

ENV_FILE="${1:?Usage: $0 <env-file>}"
source "$ENV_FILE"

# Point the CLI at the configured subscription so `az` calls target it.
# Blank AZURE_SUBSCRIPTION keeps the CLI's current default.
if [[ -n "${AZURE_SUBSCRIPTION:-}" ]]; then
  echo "==> Using Azure subscription: $AZURE_SUBSCRIPTION"
  az account set --subscription "$AZURE_SUBSCRIPTION"
fi

PUBLIC_IP=$(az network public-ip show \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "${AZURE_VM_NAME}-pip" \
  --query "ipAddress" -o tsv 2>/dev/null || echo "NOT FOUND")

SSH_TARGET="${AZURE_ADMIN_USER}@${PUBLIC_IP}"
SSH_OPTS="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"

echo "==> RentAxis Status: $AZURE_VM_NAME"
echo "    IP:     $PUBLIC_IP"
echo "    Domain: $DOMAIN"
echo ""

# Azure VM status
echo "==> Azure VM Status:"
az vm get-instance-view \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_VM_NAME" \
  --query "instanceView.statuses[1].displayStatus" -o tsv 2>/dev/null || echo "VM not found"

echo ""
echo "==> Docker Services:"
ssh $SSH_OPTS "$SSH_TARGET" "cd /opt/rentaxis && docker compose ps" 2>/dev/null || echo "Cannot reach VM"

echo ""
echo "==> Disk Usage:"
ssh $SSH_OPTS "$SSH_TARGET" "df -h /data" 2>/dev/null || echo "Cannot reach VM"

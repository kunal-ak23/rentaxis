#!/usr/bin/env bash
# =============================================================================
# teardown.sh — Destroy all Azure resources for a RentAxis environment
#
# Usage: ./infra/azure/teardown.sh <env-file>
#
# WARNING: This deletes EVERYTHING including the database disk!
# =============================================================================
set -euo pipefail

ENV_FILE="${1:?Usage: $0 <env-file>}"
source "$ENV_FILE"

echo "!!! WARNING !!!"
echo "This will DELETE the entire resource group: $AZURE_RESOURCE_GROUP"
echo "Including: VM, data disk (database), storage account, network resources"
echo ""
read -r -p "Type the resource group name to confirm: " CONFIRM

if [[ "$CONFIRM" != "$AZURE_RESOURCE_GROUP" ]]; then
  echo "Aborted."
  exit 1
fi

echo "==> Deleting resource group: $AZURE_RESOURCE_GROUP..."
az group delete --name "$AZURE_RESOURCE_GROUP" --yes --no-wait

echo "==> Deletion initiated (runs in background). Check Azure portal for status."

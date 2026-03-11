#!/usr/bin/env bash
# =============================================================================
# provision.sh — Create or update Azure infrastructure for RentAxis
#
# Usage:
#   ./infra/azure/provision.sh <env-file>
#   ./infra/azure/provision.sh infra/envs/dev.env
#
# Idempotent: safe to re-run. Creates resources only if they don't exist.
# Prerequisites: az cli logged in (`az login`)
# =============================================================================
set -euo pipefail

ENV_FILE="${1:?Usage: $0 <env-file>}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: Environment file '$ENV_FILE' not found."
  echo "Copy a template:  cp infra/envs/dev.env.template infra/envs/dev.env"
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

echo "==> Provisioning RentAxis infrastructure"
echo "    Resource Group : $AZURE_RESOURCE_GROUP"
echo "    Location       : $AZURE_LOCATION"
echo "    VM             : $AZURE_VM_NAME ($AZURE_VM_SIZE)"
echo "    Disk           : ${AZURE_DISK_SIZE_GB}GB"
echo "    Storage Account: $AZURE_STORAGE_ACCOUNT"
echo ""

# ---- Resource Group ----
echo "==> Creating resource group (if not exists)..."
az group create \
  --name "$AZURE_RESOURCE_GROUP" \
  --location "$AZURE_LOCATION" \
  --output none

# ---- Network Security Group ----
NSG_NAME="${AZURE_VM_NAME}-nsg"
echo "==> Creating NSG: $NSG_NAME..."
az network nsg create \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$NSG_NAME" \
  --output none 2>/dev/null || true

# Allow SSH, HTTP, HTTPS only
for rule in "allow-ssh 22 100" "allow-http 80 200" "allow-https 443 300"; do
  read -r name port priority <<< "$rule"
  az network nsg rule create \
    --resource-group "$AZURE_RESOURCE_GROUP" \
    --nsg-name "$NSG_NAME" \
    --name "$name" \
    --priority "$priority" \
    --destination-port-ranges "$port" \
    --access Allow \
    --protocol Tcp \
    --output none 2>/dev/null || true
done

# ---- Public IP ----
PIP_NAME="${AZURE_VM_NAME}-pip"
echo "==> Creating public IP: $PIP_NAME..."
az network public-ip create \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$PIP_NAME" \
  --sku Standard \
  --allocation-method Static \
  --dns-name "$AZURE_DNS_LABEL" \
  --output none 2>/dev/null || true

# ---- Virtual Machine ----
echo "==> Creating VM: $AZURE_VM_NAME..."
VM_EXISTS=$(az vm show --resource-group "$AZURE_RESOURCE_GROUP" --name "$AZURE_VM_NAME" --query "name" -o tsv 2>/dev/null || echo "")

if [[ -z "$VM_EXISTS" ]]; then
  az vm create \
    --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$AZURE_VM_NAME" \
    --image Ubuntu2404 \
    --size "$AZURE_VM_SIZE" \
    --admin-username "$AZURE_ADMIN_USER" \
    --generate-ssh-keys \
    --public-ip-address "$PIP_NAME" \
    --nsg "$NSG_NAME" \
    --os-disk-size-gb 30 \
    --output none

  echo "    VM created."
else
  echo "    VM already exists, skipping."
fi

# ---- Data Disk (for PostgreSQL) ----
DISK_NAME="${AZURE_VM_NAME}-data-disk"
echo "==> Creating data disk: $DISK_NAME (${AZURE_DISK_SIZE_GB}GB)..."

DISK_EXISTS=$(az disk show --resource-group "$AZURE_RESOURCE_GROUP" --name "$DISK_NAME" --query "name" -o tsv 2>/dev/null || echo "")

if [[ -z "$DISK_EXISTS" ]]; then
  az disk create \
    --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$DISK_NAME" \
    --size-gb "$AZURE_DISK_SIZE_GB" \
    --sku Premium_LRS \
    --output none

  echo "    Disk created. Attaching to VM..."
  az vm disk attach \
    --resource-group "$AZURE_RESOURCE_GROUP" \
    --vm-name "$AZURE_VM_NAME" \
    --name "$DISK_NAME" \
    --output none

  echo "    Disk attached."
else
  echo "    Disk already exists, skipping."
fi

# ---- Storage Account (for tenant blob storage) ----
echo "==> Creating storage account: $AZURE_STORAGE_ACCOUNT..."
SA_EXISTS=$(az storage account show --resource-group "$AZURE_RESOURCE_GROUP" --name "$AZURE_STORAGE_ACCOUNT" --query "name" -o tsv 2>/dev/null || echo "")

if [[ -z "$SA_EXISTS" ]]; then
  az storage account create \
    --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$AZURE_STORAGE_ACCOUNT" \
    --sku Standard_LRS \
    --kind StorageV2 \
    --access-tier Hot \
    --allow-blob-public-access false \
    --min-tls-version TLS1_2 \
    --output none

  echo "    Storage account created."
else
  echo "    Storage account already exists, skipping."
fi

# Retrieve connection string
CONN_STRING=$(az storage account show-connection-string \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_STORAGE_ACCOUNT" \
  --query "connectionString" -o tsv)

# ---- Print Summary ----
PUBLIC_IP=$(az network public-ip show \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$PIP_NAME" \
  --query "ipAddress" -o tsv)

FQDN=$(az network public-ip show \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$PIP_NAME" \
  --query "dnsSettings.fqdn" -o tsv)

echo ""
echo "============================================================"
echo "  Provisioning complete!"
echo "============================================================"
echo "  Public IP  : $PUBLIC_IP"
echo "  FQDN       : $FQDN"
echo "  SSH        : ssh ${AZURE_ADMIN_USER}@${PUBLIC_IP}"
echo ""
echo "  Storage connection string (add to your env file):"
echo "  AZURE_STORAGE_CONNECTION_STRING=$CONN_STRING"
echo ""
echo "  Next steps:"
echo "    1. Update AZURE_STORAGE_CONNECTION_STRING in your env file"
echo "    2. Run: ./infra/azure/setup-server.sh <env-file>"
echo "    3. Run: ./infra/deploy.sh <env-file>"
echo "============================================================"

#!/bin/bash
# ============================================================
# RentAxis — Provision Azure Communication Services for Email
# ============================================================
# Prerequisites:
#   - Azure CLI installed (az --version)
#   - Logged in (az login)
#   - Resource group already exists
#
# Usage:
#   chmod +x infra/provision-email.sh
#   ./infra/provision-email.sh
# ============================================================

set -euo pipefail

# ── Load config ────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${1:-$SCRIPT_DIR/envs/prod.env}"

if [ -f "$CONFIG_FILE" ]; then
    echo "Loading config from: $CONFIG_FILE"
    set -a
    source "$CONFIG_FILE"
    set +a
else
    echo "WARNING: Config file not found at $CONFIG_FILE. Using defaults."
fi

# ── Configuration (from config.env or defaults) ────────────
RESOURCE_GROUP="${AZURE_RESOURCE_GROUP:-rentaxis-rg}"
LOCATION="${AZURE_LOCATION:-uaenorth}"
COMM_SERVICE_NAME="${AZURE_COMM_SERVICE_NAME:-rentaxis-comm}"
EMAIL_SERVICE_NAME="${AZURE_EMAIL_SERVICE_NAME:-rentaxis-email}"
ENV_FILE="$CONFIG_FILE"

echo "================================================"
echo "  RentAxis — Azure Communication Services Setup"
echo "================================================"
echo ""
echo "Resource Group: $RESOURCE_GROUP"
echo "Location:       $LOCATION"
echo "Comm Service:   $COMM_SERVICE_NAME"
echo "Email Service:  $EMAIL_SERVICE_NAME"
echo ""

# ── Check prerequisites ────────────────────────────────────
if ! command -v az &> /dev/null; then
    echo "ERROR: Azure CLI not found. Install it: https://learn.microsoft.com/en-us/cli/azure/install-azure-cli"
    exit 1
fi

# Check if logged in
az account show &> /dev/null || {
    echo "ERROR: Not logged in to Azure. Run: az login"
    exit 1
}

echo "Logged in as: $(az account show --query user.name -o tsv)"
echo "Subscription: $(az account show --query name -o tsv)"
echo ""

# ── Step 1: Create Communication Service ───────────────────
echo "▸ Creating Azure Communication Service..."
az communication create \
    --name "$COMM_SERVICE_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "Global" \
    --data-location "UAE" \
    2>/dev/null || echo "  (already exists)"

echo "  ✓ Communication Service ready"

# ── Step 2: Create Email Communication Service ─────────────
echo "▸ Creating Email Communication Service..."
az communication email create \
    --name "$EMAIL_SERVICE_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "Global" \
    --data-location "UAE" \
    2>/dev/null || echo "  (already exists)"

echo "  ✓ Email Service ready"

# ── Step 3: Create Azure-managed email domain ──────────────
echo "▸ Creating Azure-managed email domain..."
az communication email domain create \
    --domain-name "AzureManagedDomain" \
    --email-service-name "$EMAIL_SERVICE_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "Global" \
    --domain-management "AzureManagedDomain" \
    2>/dev/null || echo "  (already exists)"

echo "  ✓ Email domain ready"

# ── Step 4: Link email domain to communication service ─────
echo "▸ Linking email domain to communication service..."
EMAIL_DOMAIN_ID=$(az communication email domain show \
    --domain-name "AzureManagedDomain" \
    --email-service-name "$EMAIL_SERVICE_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query id -o tsv 2>/dev/null || echo "")

if [ -n "$EMAIL_DOMAIN_ID" ]; then
    az communication update \
        --name "$COMM_SERVICE_NAME" \
        --resource-group "$RESOURCE_GROUP" \
        --linked-domains "$EMAIL_DOMAIN_ID" \
        2>/dev/null || echo "  (link may already exist)"
    echo "  ✓ Domain linked"
else
    echo "  ⚠ Could not find email domain ID. Link manually in Azure Portal."
fi

# ── Step 5: Get connection string ──────────────────────────
echo "▸ Retrieving connection string..."
CONN_STRING=$(az communication list-key \
    --name "$COMM_SERVICE_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query primaryConnectionString -o tsv 2>/dev/null || echo "")

if [ -z "$CONN_STRING" ]; then
    echo "  ⚠ Could not retrieve connection string. Get it from Azure Portal:"
    echo "    Portal → Communication Services → $COMM_SERVICE_NAME → Keys"
else
    echo "  ✓ Connection string retrieved"
fi

# ── Step 6: Get sender email address ───────────────────────
echo "▸ Getting sender email address..."
SENDER_DOMAIN=$(az communication email domain show \
    --domain-name "AzureManagedDomain" \
    --email-service-name "$EMAIL_SERVICE_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query mailFromSenderDomain -o tsv 2>/dev/null || echo "")

SENDER_ADDRESS="DoNotReply@${SENDER_DOMAIN}"
if [ -z "$SENDER_DOMAIN" ]; then
    SENDER_ADDRESS="DoNotReply@xxxxxxxx.azurecomm.net"
    echo "  ⚠ Could not determine sender domain. Check Azure Portal."
else
    echo "  ✓ Sender: $SENDER_ADDRESS"
fi

# ── Step 7: Update .env.backend ────────────────────────────
echo ""
echo "▸ Updating $ENV_FILE..."

# Remove old entries if they exist
grep -v "^AZURE_COMMUNICATION_CONNECTION_STRING" "$ENV_FILE" > "${ENV_FILE}.tmp" 2>/dev/null || true
grep -v "^AZURE_EMAIL_SENDER" "${ENV_FILE}.tmp" > "$ENV_FILE" 2>/dev/null || true
rm -f "${ENV_FILE}.tmp"

# Append new entries
{
    echo ""
    echo "# Azure Communication Services (Email)"
    echo "AZURE_COMMUNICATION_CONNECTION_STRING=$CONN_STRING"
    echo "AZURE_EMAIL_SENDER=$SENDER_ADDRESS"
} >> "$ENV_FILE"

echo "  ✓ Environment variables added to $ENV_FILE"

# ── Summary ────────────────────────────────────────────────
echo ""
echo "================================================"
echo "  Setup Complete!"
echo "================================================"
echo ""
echo "Communication Service: $COMM_SERVICE_NAME"
echo "Email Service:         $EMAIL_SERVICE_NAME"
echo "Sender Address:        $SENDER_ADDRESS"
echo ""
if [ -n "$CONN_STRING" ]; then
    echo "Connection string saved to $ENV_FILE"
else
    echo "⚠ Add AZURE_COMMUNICATION_CONNECTION_STRING manually to $ENV_FILE"
fi
echo ""
echo "Next steps:"
echo "  1. Restart the backend to pick up new env vars"
echo "  2. Test by triggering a notification (e.g., assign a ticket)"
echo "  3. Check Azure Portal → Communication Services → Email → Logs"
echo ""

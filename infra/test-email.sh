#!/bin/bash
# ============================================================
# RentAxis — Test Azure Communication Services Email
# ============================================================
# Usage:
#   chmod +x infra/test-email.sh
#   ./infra/test-email.sh recipient@example.com
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load env
if [ -f "$SCRIPT_DIR/envs/prod.env" ]; then
    set -a
    source "$SCRIPT_DIR/envs/prod.env"
    set +a
fi

# Also load local .env.backend (overrides)
if [ -f ".env.backend" ]; then
    set -a
    source ".env.backend"
    set +a
fi

RECIPIENT="${1:-}"

if [ -z "$RECIPIENT" ]; then
    echo "Usage: ./infra/test-email.sh your-email@example.com"
    exit 1
fi

echo "================================================"
echo "  RentAxis — Email Test"
echo "================================================"
echo ""
echo "Connection String: ${AZURE_COMMUNICATION_CONNECTION_STRING:0:60}..."
echo "Sender:            ${AZURE_EMAIL_SENDER:-NOT SET}"
echo "Recipient:         $RECIPIENT"
echo ""

# Check if connection string is set
if [ -z "${AZURE_COMMUNICATION_CONNECTION_STRING:-}" ]; then
    echo "ERROR: AZURE_COMMUNICATION_CONNECTION_STRING is not set"
    exit 1
fi

if [ -z "${AZURE_EMAIL_SENDER:-}" ]; then
    echo "ERROR: AZURE_EMAIL_SENDER is not set"
    exit 1
fi

# Extract endpoint from connection string (macOS compatible)
ENDPOINT=$(echo "$AZURE_COMMUNICATION_CONNECTION_STRING" | sed 's/.*endpoint=\([^;]*\).*/\1/')
ACCESS_KEY=$(echo "$AZURE_COMMUNICATION_CONNECTION_STRING" | sed 's/.*accesskey=\([^;]*\).*/\1/')

echo "Endpoint: $ENDPOINT"
echo ""

# Use Azure CLI to send test email
echo "▸ Attempting to send test email via Azure CLI..."

# Method 1: Try az rest with the Communication Services REST API
# The email API endpoint is: POST {endpoint}/emails:send?api-version=2023-03-31

# Build the request body
REQUEST_BODY=$(cat <<EOF
{
  "senderAddress": "${AZURE_EMAIL_SENDER}",
  "content": {
    "subject": "RentAxis - Test Email",
    "plainText": "Hello!\n\nThis is a test email from RentAxis Property Management System.\n\nIf you received this, the Azure Communication Services email integration is working correctly.\n\nTimestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")\n\n---\nRentAxis"
  },
  "recipients": {
    "to": [
      {
        "address": "${RECIPIENT}",
        "displayName": "Test Recipient"
      }
    ]
  }
}
EOF
)

echo ""
echo "Request body:"
echo "$REQUEST_BODY" | python3 -m json.tool 2>/dev/null || echo "$REQUEST_BODY"
echo ""

# Try sending via curl with HMAC auth (simplified — use az CLI instead)
echo "▸ Sending via Azure CLI..."

# Use az communication email send if available
if az communication email send --help &>/dev/null 2>&1; then
    az communication email send \
        --connection-string "$AZURE_COMMUNICATION_CONNECTION_STRING" \
        --sender "$AZURE_EMAIL_SENDER" \
        --to "$RECIPIENT" \
        --subject "RentAxis - Test Email" \
        --text "Hello! This is a test email from RentAxis. Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
        2>&1

    if [ $? -eq 0 ]; then
        echo ""
        echo "✓ Email sent successfully! Check $RECIPIENT inbox (and spam folder)."
    else
        echo ""
        echo "✗ Failed to send email via az CLI."
    fi
else
    echo ""
    echo "Azure CLI 'az communication email send' not available."
    echo "Trying Python SDK instead..."
    echo ""

    # Fallback: Use Python with azure-communication-email
    python3 -c "
from azure.communication.email import EmailClient

conn_str = '${AZURE_COMMUNICATION_CONNECTION_STRING}'
sender = '${AZURE_EMAIL_SENDER}'
recipient = '${RECIPIENT}'

print(f'Connecting to Azure Communication Services...')
client = EmailClient.from_connection_string(conn_str)

message = {
    'senderAddress': sender,
    'content': {
        'subject': 'RentAxis - Test Email',
        'plainText': 'Hello!\n\nThis is a test email from RentAxis.\nTimestamp: $(date -u)'
    },
    'recipients': {
        'to': [{'address': recipient, 'displayName': 'Test'}]
    }
}

print(f'Sending email from {sender} to {recipient}...')
poller = client.begin_send(message)
result = poller.result()
print(f'Status: {result[\"status\"]}')
print(f'Message ID: {result.get(\"id\", \"N/A\")}')
print()
print('✓ Email sent successfully!')
" 2>&1 || {
        echo ""
        echo "Python SDK not installed. Install with:"
        echo "  pip3 install azure-communication-email"
        echo ""
        echo "Or test manually with curl:"
        echo "  See https://learn.microsoft.com/en-us/azure/communication-services/quickstarts/email/send-email"
    }
fi

echo ""
echo "================================================"

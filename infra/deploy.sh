#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Build, ship, and deploy RentAxis to an Azure VM
#
# Usage:
#   ./infra/deploy.sh <env-file>
#   ./infra/deploy.sh infra/envs/dev.env
#
# What it does:
#   1. Builds Docker images locally
#   2. Saves them as tarballs
#   3. Uploads to the VM via SSH
#   4. Loads images and (re)starts the stack
#
# Idempotent: updates the running instance if it already exists.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${1:?Usage: $0 <env-file>}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: Environment file '$ENV_FILE' not found."
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

# Point the CLI at the configured subscription so the `az` public-ip lookup
# below resolves the VM in the right subscription. Blank keeps the CLI default.
if [[ -n "${AZURE_SUBSCRIPTION:-}" ]]; then
  echo "==> Using Azure subscription: $AZURE_SUBSCRIPTION"
  az account set --subscription "$AZURE_SUBSCRIPTION"
fi

# ---- Configuration ----
PUBLIC_IP=$(az network public-ip show \
  --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "${AZURE_VM_NAME}-pip" \
  --query "ipAddress" -o tsv)

SSH_TARGET="${AZURE_ADMIN_USER}@${PUBLIC_IP}"
SSH_OPTS="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"
REMOTE_DIR="/opt/rentaxis"
BUILD_DIR="/tmp/rentaxis-deploy-$$"

echo "==> Deploying RentAxis to $SSH_TARGET"
echo "    Environment: $ENV_FILE"
echo "    Domain:      $DOMAIN"
echo ""

# ---- Step 1: Build Docker images locally ----
echo "==> Building Docker images..."
cd "$PROJECT_ROOT"

TARGET_PLATFORM="linux/amd64"

docker build --platform "$TARGET_PLATFORM" -t rentaxis-backend:latest ./backend
docker build --platform "$TARGET_PLATFORM" \
  --build-arg NEXT_PUBLIC_API_URL="${NEXT_PUBLIC_API_URL}" \
  --build-arg BACKEND_URL="http://backend:8080" \
  -t rentaxis-web:latest ./web

# ---- Step 2: Save images as tarballs ----
echo "==> Saving images to tarballs..."
mkdir -p "$BUILD_DIR"

docker save rentaxis-backend:latest | gzip > "$BUILD_DIR/backend.tar.gz"
docker save rentaxis-web:latest | gzip > "$BUILD_DIR/web.tar.gz"

echo "    Backend image: $(du -sh "$BUILD_DIR/backend.tar.gz" | cut -f1)"
echo "    Web image:     $(du -sh "$BUILD_DIR/web.tar.gz" | cut -f1)"

# ---- Step 3: Upload to VM ----
echo "==> Uploading to $SSH_TARGET..."

# Ensure remote directory exists
ssh $SSH_OPTS "$SSH_TARGET" "mkdir -p $REMOTE_DIR"

# Upload images
scp $SSH_OPTS "$BUILD_DIR/backend.tar.gz" "$SSH_TARGET:$REMOTE_DIR/"
scp $SSH_OPTS "$BUILD_DIR/web.tar.gz" "$SSH_TARGET:$REMOTE_DIR/"

# Upload compose file and Caddyfile
scp $SSH_OPTS "$PROJECT_ROOT/docker-compose.prod.yml" "$SSH_TARGET:$REMOTE_DIR/docker-compose.yml"

# Caddy reads {$DOMAIN} from its environment (set via docker compose)
scp $SSH_OPTS "$PROJECT_ROOT/infra/caddy/Caddyfile.template" "$SSH_TARGET:$REMOTE_DIR/Caddyfile"

# Upload env file (renamed to .env for docker compose)
scp $SSH_OPTS "$ENV_FILE" "$SSH_TARGET:$REMOTE_DIR/.env"

# ---- Step 4: Load images and restart on the VM ----
echo "==> Loading images and starting services on VM..."

ssh $SSH_OPTS "$SSH_TARGET" bash -s <<REMOTE_DEPLOY
set -euo pipefail
cd $REMOTE_DIR

echo "    Loading backend image..."
docker load < backend.tar.gz

echo "    Loading web image..."
docker load < web.tar.gz

echo "    Cleaning up tarballs..."
rm -f backend.tar.gz web.tar.gz

echo "    Pulling latest caddy and postgres images..."
docker compose pull caddy postgres

echo "    Starting/updating services..."
docker compose up -d --remove-orphans

echo "    Waiting for services to be healthy..."
sleep 5
docker compose ps

echo "    Pruning unused images..."
docker image prune -f
REMOTE_DEPLOY

# ---- Cleanup local build artifacts ----
rm -rf "$BUILD_DIR"

echo ""
echo "============================================================"
echo "  Deployment complete!"
echo "============================================================"
echo "  URL: https://$DOMAIN"
echo "  SSH: ssh $SSH_TARGET"
echo ""
echo "  Useful commands (on the VM):"
echo "    cd $REMOTE_DIR"
echo "    docker compose logs -f          # tail all logs"
echo "    docker compose logs -f backend  # tail backend logs"
echo "    docker compose restart backend  # restart backend"
echo "    docker compose down             # stop everything"
echo "    docker compose up -d            # start everything"
echo "============================================================"

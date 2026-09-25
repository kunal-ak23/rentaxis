#!/usr/bin/env bash
# Start a backend on :8082 against the rentaxis_scale database (scale test only).
# Outbound email/AI are blanked so a scale seed never sends anything.
set -euo pipefail
cd "$(dirname "$0")/../../backend"
set -a; source ../.env.backend; set +a
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/${SCALE_DB:-rentaxis_scale}
export RENTAXIS_GATEWAY_STUB_ENABLED=true SERVER_PORT=${SCALE_PORT:-8082}
export AZURE_COMMUNICATION_CONNECTION_STRING= AZURE_OPENAI_API_KEY= AZURE_OPENAI_ENDPOINT=
export SPRING_JPA_OPEN_IN_VIEW=${SCALE_OSIV:-false}  # production default since scale PR A; SCALE_OSIV=true for a before run
export SPRING_JPA_SHOW_SQL=false SPRING_PROFILES_ACTIVE=${SCALE_PROFILE:-scale}   # not "dev": dev turns on TRACE web/security logging
export RENTAXIS_RECOGNITION_JOB_CATCH_UP_ENABLED=false
if [ "${SCALE_HIBERNATE_STATS:-false}" = true ]; then
  # Per-session "N JDBC statements executed" lines, which measure.py --count-queries sums.
  export SPRING_APPLICATION_JSON='{"spring.jpa.properties.hibernate.generate_statistics":true,"logging.level.org.hibernate.session.metrics":"DEBUG"}'
fi
exec ./gradlew bootRun --console=plain

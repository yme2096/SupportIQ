#!/bin/sh
set -eu

# Render provides postgresql://user:password@host:port/database; pgJDBC expects
# credentials as URL parameters rather than in the URI authority.
if [ -n "${DATABASE_URL:-}" ]; then
  case "$DATABASE_URL" in
    jdbc:postgresql://*) DB_URL="$DATABASE_URL" ;;
    postgres://*|postgresql://*)
      rest=${DATABASE_URL#*://}
      authority=${rest%%/*}
      database_path=/${rest#*/}
      credentials=${authority%@*}
      host_port=${authority##*@}
      case "$credentials" in
        *:*) db_user=${credentials%%:*}; db_password=${credentials#*:} ;;
        *) echo "DATABASE_URL must include a username and password" >&2; exit 64 ;;
      esac
      case "$database_path" in
        *\?*) DB_URL="jdbc:postgresql://${host_port}${database_path}&user=${db_user}&password=${db_password}" ;;
        *) DB_URL="jdbc:postgresql://${host_port}${database_path}?user=${db_user}&password=${db_password}" ;;
      esac
      ;;
    *) echo "DATABASE_URL must use postgres:// or postgresql://" >&2; exit 64 ;;
  esac
  export DB_URL
fi

service_mode="${1:-web}"
active_profile="${SPRING_PROFILES_ACTIVE:-production}"
if [ "$service_mode" = "worker" ]; then
  shift
  exec java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar /app/app.jar \
    --spring.profiles.active="$active_profile" \
    --app.ai.worker.enabled=true \
    --app.ai.processing.dispatch-locally=false "$@"
fi

if [ "$service_mode" = "web" ]; then shift; fi
exec java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar /app/app.jar \
  --spring.profiles.active="$active_profile" "$@"

#!/usr/bin/env bash
# Start local MySQL (tarball) + SAMS API without Docker.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk-21}"
export PATH="$JAVA_HOME/bin:$HOME/.local/mysql/bin:$PATH"
export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}:$HOME/.local/lib"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOCK=/tmp/sams-mysql.sock
if ! mysqladmin --socket="$SOCK" -uroot ping >/dev/null 2>&1; then
  "$HOME/.local/mysql/bin/mysqld" \
    --datadir="$HOME/.local/mysql-data" \
    --basedir="$HOME/.local/mysql" \
    --port=3306 --socket="$SOCK" \
    --bind-address=127.0.0.1 --mysqlx=0 \
    --daemonize --pid-file=/tmp/sams-mysql.pid \
    --log-error=/tmp/sams-mysql.err
fi
export GEO_DATA_PATH="$ROOT/deploy/geo_data/ne_10m_admin_0_countries.geojson"
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_USER=sams DB_PASSWORD=sams DB_NAME=sams
BE="$ROOT/backend"
CP="$(paste -sd: "$BE/.classpath-jars"):$BE/target/classes"
exec java -cp "$CP" com.sams.SamsApplication

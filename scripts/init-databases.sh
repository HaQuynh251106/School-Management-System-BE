#!/usr/bin/env bash
# ============================================================
# Tạo 4 database trong 4 PostgreSQL instance dev local.
# Sử dụng sau khi `docker compose -f docker-compose.dev.yml up -d`.
#
# Mỗi service đã có sẵn DB từ env POSTGRES_DB, script này dùng
# khi cần reset / tạo lại CSDL.
#
# TODO (P1 - S1): hoàn thiện theo nhu cầu thực tế.
# ============================================================
set -e

echo "[init-databases] Smart School Ecosystem"
echo "[1/4] identity_db ......... ok (sẵn từ docker-compose)"
echo "[2/4] academic_db ......... ok"
echo "[3/4] finance_db .......... ok"
echo "[4/4] notification_db ..... ok"
echo "Done. Chạy Flyway: cd services/<name> && mvn flyway:migrate"

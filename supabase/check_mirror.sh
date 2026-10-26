#!/usr/bin/env bash
# Bảo đảm mọi object do migrations/*.sql tạo ra đều có mặt trong full_schema_singapore.sql
# (convention: mỗi migration phải được mirror vào full_schema — xem memory Supabase conventions).
# Cách dùng: bash supabase/check_mirror.sh   (exit 1 + danh sách object thiếu nếu có lệch)
set -u
cd "$(dirname "$0")"
schema=full_schema_singapore.sql
missing=0

# 1. Function / table / view: lấy tên public.<name> từ các lệnh CREATE trong migrations
names=$(grep -hoiE 'create (or replace )?(table|function|view|materialized view) (if not exists )?public\.[a-z0-9_]+' migrations/*.sql \
  | awk '{print $NF}' | sed 's/public\.//' | sort -u)
for n in $names; do
  if ! grep -qE "(public\.$n|create (or replace )?(table|function|view|materialized view)( if not exists)? public\.$n)\b" "$schema"; then
    if ! grep -qE "public\.$n\b" "$schema"; then
      echo "THIEU: public.$n (co trong migrations/ nhung khong co trong $schema)"
      missing=1
    fi
  fi
done

# 2. Cột do 'alter table ... add column' thêm trong migrations phải được nhắc tới trong schema
cols=$(grep -hoiE 'alter table public\.[a-z0-9_]+ add column( if not exists)? [a-z0-9_]+' migrations/*.sql \
  | awk '{print $NF}' | sort -u)
for c in $cols; do
  if ! grep -qiE "\b$c\b" "$schema"; then
    echo "THIEU COT: $c (alter trong migrations/ nhung khong thay trong $schema)"
    missing=1
  fi
done

if [ $missing -eq 0 ]; then
  echo "OK: moi object cua migrations/ deu co mat trong $schema"
fi
exit $missing

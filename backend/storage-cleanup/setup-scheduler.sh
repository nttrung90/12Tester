#!/usr/bin/env bash
# ==============================================================================
# Setup Cloud Run Job & Cloud Scheduler cho worker dọn rác Storage
# Bucket: cross-testers.firebasestorage.app
# Tần suất: Chạy mỗi giờ (0 * * * *)
# ==============================================================================

set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:-crosstest-prod}"
REGION="${GCP_REGION:-asia-southeast1}"
JOB_NAME="storage-reports-cleaner"
SCHEDULER_NAME="trigger-storage-reports-cleaner"
SERVICE_ACCOUNT_NAME="storage-cleaner-sa"
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
IMAGE_URL="gcr.io/${PROJECT_ID}/${JOB_NAME}:latest"
BUCKET_NAME="cross-testers.firebasestorage.app"

echo "=== 1. Thiết lập Project ==="
gcloud config set project "${PROJECT_ID}"

echo "=== 2. Tạo Service Account tối thiểu (nếu chưa có) ==="
if ! gcloud iam service-accounts describe "${SERVICE_ACCOUNT_EMAIL}" >/dev/null 2>&1; then
    gcloud iam service-accounts create "${SERVICE_ACCOUNT_NAME}" \
        --display-name="Storage Reports Cleanup Worker" \
        --description="Service account chạy Cloud Run Job dọn screenshot báo cáo quá hạn 30 ngày"
fi

echo "=== 3. Cấp quyền IAM tối thiểu (chỉ thao tác trên Storage) ==="
# Cấp quyền Storage Object User trên bucket
gsutil iam ch "serviceAccount:${SERVICE_ACCOUNT_EMAIL}:objectAdmin" "gs://${BUCKET_NAME}"

# Cấp quyền gọi Cloud Run Job cho Service Account
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SERVICE_ACCOUNT_EMAIL}" \
    --role="roles/run.invoker"

echo "=== 4. Áp dụng Lifecycle Rule dự phòng cho GCS Bucket ==="
gsutil lifecycle set gcs-lifecycle-reports.json "gs://${BUCKET_NAME}"

echo "=== 5. Build Container Image ==="
gcloud builds submit --tag "${IMAGE_URL}" .

echo "=== 6. Deploy hoặc cập nhật Cloud Run Job ==="
gcloud run jobs deploy "${JOB_NAME}" \
    --image="${IMAGE_URL}" \
    --region="${REGION}" \
    --service-account="${SERVICE_ACCOUNT_EMAIL}" \
    --set-env-vars="STORAGE_BUCKET=${BUCKET_NAME},TARGET_AGE_DAYS=29,SLA_THRESHOLD_DAYS=30" \
    --max-retries=2 \
    --task-timeout=15m

echo "=== 7. Tạo Cloud Scheduler định kỳ mỗi giờ ==="
if gcloud scheduler jobs describe "${SCHEDULER_NAME}" --location="${REGION}" >/dev/null 2>&1; then
    echo "Cập nhật Scheduler Job hiện có..."
    gcloud scheduler jobs update http "${SCHEDULER_NAME}" \
        --location="${REGION}" \
        --schedule="0 * * * *" \
        --uri="https://${REGION}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${PROJECT_ID}/jobs/${JOB_NAME}:run" \
        --http-method=POST \
        --oauth-service-account-email="${SERVICE_ACCOUNT_EMAIL}" \
        --time-zone="Asia/Ho_Chi_Minh"
else
    echo "Tạo mới Scheduler Job..."
    gcloud scheduler jobs create http "${SCHEDULER_NAME}" \
        --location="${REGION}" \
        --schedule="0 * * * *" \
        --uri="https://${REGION}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${PROJECT_ID}/jobs/${JOB_NAME}:run" \
        --http-method=POST \
        --oauth-service-account-email="${SERVICE_ACCOUNT_EMAIL}" \
        --time-zone="Asia/Ho_Chi_Minh"
fi

echo "=== HOÀN TẤT TRIỂN KHAI WORKER DỌN RÁC STORAGE ==="

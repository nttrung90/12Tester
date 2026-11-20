# Dockerfile cho Cloud Run Job dọn dẹp Storage
FROM node:20-alpine

WORKDIR /app

# Cài đặt dependencies với package lock
COPY package*.json ./
RUN npm ci --omit=dev

# Sao chép mã nguồn worker
COPY cleanup-reports-storage.mjs ./

ENV NODE_ENV=production

# Chạy worker dọn dẹp
CMD ["node", "cleanup-reports-storage.mjs"]

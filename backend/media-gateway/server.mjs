#!/usr/bin/env node

/**
 * Bootstrap Server cho Media Access Gateway (E04).
 *
 * Kiểm tra các biến môi trường bắt buộc trước khi khởi chạy:
 * - MEDIA_GATEWAY_SECRET: Khóa bí mật HMAC 256-bit (tối thiểu 32 ký tự).
 * - SUPABASE_URL: URL dự án Supabase.
 * - SUPABASE_SERVICE_ROLE_KEY: Service role key để truy vấn database xác thực quyền.
 *
 * Nếu thiếu bất kỳ cấu hình nào, tiến trình sẽ FAIL CLOSED (exit 1).
 */

import http from 'node:http';
import { createClient } from '@supabase/supabase-js';
import { Storage } from '@google-cloud/storage';
import { createMediaGatewayHandler } from './media-gateway-handler.mjs';
import { SupabaseAuthorityAdapter } from './adapters/supabase-authority-adapter.mjs';
import { FirebaseStorageAdapter } from './adapters/storage-adapter.mjs';

const PORT = Number.parseInt(process.env.PORT || '8080', 10);
const GATEWAY_SECRET = process.env.MEDIA_GATEWAY_SECRET;
const SUPABASE_URL = process.env.SUPABASE_URL;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;
const STORAGE_BUCKET = process.env.FIREBASE_STORAGE_BUCKET || 'cross-testers.firebasestorage.app';

if (!GATEWAY_SECRET || GATEWAY_SECRET.length < 32) {
    console.error('FATAL: MEDIA_GATEWAY_SECRET chưa được cấu hình hoặc ngắn hơn 32 ký tự. Media Gateway fail-closed.');
    process.exit(1);
}

if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
    console.error('FATAL: Thiếu cấu hình SUPABASE_URL hoặc SUPABASE_SERVICE_ROLE_KEY. Media Gateway fail-closed.');
    process.exit(1);
}

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
});

const authorityAdapter = new SupabaseAuthorityAdapter({
    supabaseClient: supabase,
    logger: console,
});

// Khởi tạo Google Cloud Storage authenticated client thông qua ADC (Application Default Credentials)
const storageClient = new Storage();

const storageAdapter = new FirebaseStorageAdapter({
    bucketName: STORAGE_BUCKET,
    storageClient,
    logger: console,
});

const handler = createMediaGatewayHandler({
    authorityAdapter,
    storageAdapter,
    gatewaySecret: GATEWAY_SECRET,
    logger: console,
});

const server = http.createServer(handler);

server.listen(PORT, '0.0.0.0', () => {
    console.log(`Media Access Gateway (E04) đang lắng nghe tại cổng ${PORT} [FAIL-CLOSED MODE ACTIVE]`);
});

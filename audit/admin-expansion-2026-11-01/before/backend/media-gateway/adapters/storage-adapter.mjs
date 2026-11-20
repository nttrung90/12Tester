/**
 * Storage Adapter cho Media Access Gateway (E04).
 *
 * Tải trực tiếp dữ liệu nhị phân của ảnh từ Cloud Storage thông qua authenticated client,
 * tuyệt đối KHÔNG redirect về client và KHÔNG phụ thuộc vào download token công khai.
 */

export class FirebaseStorageAdapter {
    constructor({ bucketName, storageClient = null, logger = console }) {
        this.bucketName = bucketName;
        this.storageClient = storageClient;
        this.logger = logger;
    }

    /**
     * Tải nhị phân của đối tượng từ authenticated Cloud Storage client.
     */
    async getObject({ reportId, variant, storagePath }) {
        if (!storagePath) {
            return null;
        }

        // Tiền điều kiện 1: Bắt buộc phải có authenticated storageClient.
        // Tuyệt đối không fallback gọi fetch() với public token đã bị revoke.
        if (!this.storageClient || !this.bucketName) {
            this.logger.warn?.('StorageAdapter: Cần storageClient hợp lệ để truy cập storage xác thực. Không dùng token công khai.');
            return null;
        }

        try {
            let objectName = storagePath;

            // Kiểm tra bucket allowlist từ URL trước khi gọi storageClient
            if (storagePath.startsWith('http')) {
                const urlObj = new URL(storagePath);
                let urlBucket = null;

                if (urlObj.hostname === 'storage.googleapis.com') {
                    // Định dạng: https://storage.googleapis.com/<bucket>/<object_path>
                    const parts = urlObj.pathname.split('/').filter(Boolean);
                    urlBucket = parts[0];
                    objectName = parts.slice(1).join('/');
                } else if (urlObj.hostname === 'firebasestorage.googleapis.com') {
                    // Định dạng: https://firebasestorage.googleapis.com/v0/b/<bucket>/o/<object_path>
                    const match = urlObj.pathname.match(/^\/v0\/b\/([^/]+)\/o\/(.+)$/);
                    if (match) {
                        urlBucket = match[1];
                        objectName = decodeURIComponent(match[2].replace(/\+/g, ' '));
                    }
                } else if (urlObj.hostname.endsWith('.firebasestorage.app')) {
                    urlBucket = urlObj.hostname;
                    const match = urlObj.pathname.match(/^\/v0\/b\/([^/]+)\/o\/(.+)$/);
                    if (match) {
                        urlBucket = match[1];
                        objectName = decodeURIComponent(match[2].replace(/\+/g, ' '));
                    } else if (urlObj.pathname.startsWith('/o/')) {
                        objectName = decodeURIComponent(urlObj.pathname.slice(3));
                    }
                }

                // Nếu URL chứa bucket nhưng không khớp với bucket cấu hình -> từ chối ngay lập tức
                if (urlBucket && urlBucket !== this.bucketName) {
                    this.logger.warn?.(`StorageAdapter: Bucket "${urlBucket}" trong URL không khớp với bucket cấu hình "${this.bucketName}".`);
                    return null;
                }
            }

            const bucket = this.storageClient.bucket(this.bucketName);
            const file = bucket.file(objectName);
            const [exists] = await file.exists();
            if (!exists) {
                return null;
            }

            const [metadata] = await file.getMetadata();
            const [bytes] = await file.download();

            return {
                bytes,
                contentType: metadata.contentType || 'image/jpeg',
            };
        } catch (err) {
            this.logger.error?.(`StorageAdapter getObject error: ${err.message}`);
            return null;
        }
    }
}

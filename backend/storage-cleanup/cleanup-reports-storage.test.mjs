import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
    cleanupStorage,
    ALLOWED_BUCKET,
    TARGET_PREFIX,
} from './cleanup-reports-storage.mjs';

/**
 * Mock File helper
 */
function createMockFile({ name, timeCreated, generation = '1700000000000001', size = 500000, deleteHandler }) {
    let deleted = false;
    let deleteCalls = [];

    const fileObj = {
        name,
        metadata: {
            timeCreated,
            generation,
            size,
        },
        async delete(options) {
            deleteCalls.push(options);
            if (deleteHandler) {
                return await deleteHandler(options);
            }
            deleted = true;
            return [{ status: 204 }];
        },
        _isDeleted: () => deleted,
        _getDeleteCalls: () => deleteCalls,
    };
    return fileObj;
}

/**
 * Mock Storage helper
 */
function createMockStorage(files = []) {
    return {
        bucket(bucketName) {
            return {
                name: bucketName,
                async getFiles({ prefix }) {
                    const matched = files.filter(f => f.name.startsWith(prefix));
                    return [matched];
                },
            };
        },
    };
}

describe('Storage Cleanup Worker Tests', () => {
    const fixedNow = new Date('2026-10-27T12:00:00.000Z');
    const dayMs = 24 * 60 * 60 * 1000;

    test('R01: Chỉ dọn file >= 29 ngày, giữ file < 29 ngày, cảnh báo SLA khi >= 30 ngày', async () => {
        const fileRecent = createMockFile({
            name: 'reports/camp_01/user_01/recent.jpg',
            timeCreated: new Date(fixedNow.getTime() - 10 * dayMs).toISOString(), // 10 ngày tuổi
        });

        const fileCandidate = createMockFile({
            name: 'reports/camp_01/user_02/eligible.jpg',
            timeCreated: new Date(fixedNow.getTime() - 29.5 * dayMs).toISOString(), // 29.5 ngày tuổi
            generation: 'gen_candidate_01',
        });

        const fileSlaViolation = createMockFile({
            name: 'reports/camp_02/user_03_1680000000.jpg', // định dạng cũ
            timeCreated: new Date(fixedNow.getTime() - 32 * dayMs).toISOString(), // 32 ngày tuổi (vi phạm SLA)
            generation: 'gen_sla_01',
        });

        const mockStorage = createMockStorage([fileRecent, fileCandidate, fileSlaViolation]);

        const stats = await cleanupStorage({
            storage: mockStorage,
            bucketName: ALLOWED_BUCKET,
            prefix: TARGET_PREFIX,
            now: fixedNow,
            dryRun: false,
            logger: { info: () => {}, warn: () => {}, error: () => {} },
        });

        assert.equal(stats.total_scanned, 3);
        assert.equal(stats.candidates_found, 2);
        assert.equal(stats.candidates_deleted, 2);
        assert.equal(stats.sla_violations_count, 1, 'File 32 ngày phải được tính là vi phạm SLA');

        assert.equal(fileRecent._isDeleted(), false, 'File 10 ngày tuổi không được xóa');
        assert.equal(fileCandidate._isDeleted(), true, 'File 29.5 ngày tuổi phải bị xóa');
        assert.equal(fileSlaViolation._isDeleted(), true, 'File 32 ngày tuổi phải bị xóa');

        assert.deepEqual(fileCandidate._getDeleteCalls()[0], { ifGenerationMatch: 'gen_candidate_01' });
        assert.deepEqual(fileSlaViolation._getDeleteCalls()[0], { ifGenerationMatch: 'gen_sla_01' });
    });

    test('R01: Bảo vệ an toàn - icon app_icons/ tuyệt đối không bị quét hay xóa', async () => {
        const fileIcon = createMockFile({
            name: 'app_icons/com_test_app.webp',
            timeCreated: new Date(fixedNow.getTime() - 180 * dayMs).toISOString(), // 180 ngày tuổi
        });

        const fileReport = createMockFile({
            name: 'reports/camp_01/user_01/new_rep.jpg',
            timeCreated: new Date(fixedNow.getTime() - 2 * dayMs).toISOString(),
        });

        const mockStorage = createMockStorage([fileIcon, fileReport]);

        const stats = await cleanupStorage({
            storage: mockStorage,
            bucketName: ALLOWED_BUCKET,
            prefix: TARGET_PREFIX,
            now: fixedNow,
            dryRun: false,
            logger: { info: () => {}, warn: () => {}, error: () => {} },
        });

        assert.equal(fileIcon._isDeleted(), false);
        assert.equal(stats.total_scanned, 1, 'Chỉ quét prefix reports/, không quét app_icons/');
    });

    test('R01: Cờ --dry-run tính toán chính xác nhưng không thực hiện lệnh xóa', async () => {
        const fileOld = createMockFile({
            name: 'reports/camp_01/user_01/old.jpg',
            timeCreated: new Date(fixedNow.getTime() - 30 * dayMs).toISOString(),
            size: 800000,
        });

        const mockStorage = createMockStorage([fileOld]);

        const stats = await cleanupStorage({
            storage: mockStorage,
            bucketName: ALLOWED_BUCKET,
            prefix: TARGET_PREFIX,
            now: fixedNow,
            dryRun: true,
            logger: { info: () => {}, warn: () => {}, error: () => {} },
        });

        assert.equal(stats.candidates_found, 1);
        assert.equal(stats.candidates_deleted, 0, 'Dry-run không được xóa file');
        assert.equal(stats.bytes_reclaimed, 800000);
        assert.equal(fileOld._isDeleted(), false);
    });

    test('R01: Xử lý idempotent khi file trả về 404 (đã bị xóa trước đó)', async () => {
        const fileDeletedElsewhere = createMockFile({
            name: 'reports/camp_01/user_01/ghost.jpg',
            timeCreated: new Date(fixedNow.getTime() - 29.5 * dayMs).toISOString(),
            deleteHandler: async () => {
                const err = new Error('No such object: reports/camp_01/user_01/ghost.jpg');
                err.code = 404;
                throw err;
            },
        });

        const mockStorage = createMockStorage([fileDeletedElsewhere]);

        const stats = await cleanupStorage({
            storage: mockStorage,
            bucketName: ALLOWED_BUCKET,
            prefix: TARGET_PREFIX,
            now: fixedNow,
            dryRun: false,
            logger: { info: () => {}, warn: () => {}, error: () => {} },
        });

        assert.equal(stats.already_deleted_count, 1);
        assert.equal(stats.errors_count, 0, '404 là idempotent, không tính vào lỗi');
    });

    test('R01: Xử lý an toàn khi xảy ra Precondition Failed 412 (generation mismatch)', async () => {
        const fileRaceCondition = createMockFile({
            name: 'reports/camp_01/user_01/race.jpg',
            timeCreated: new Date(fixedNow.getTime() - 29.5 * dayMs).toISOString(),
            generation: 'old_gen',
            deleteHandler: async () => {
                const err = new Error('Precondition failed');
                err.code = 412;
                throw err;
            },
        });

        const mockStorage = createMockStorage([fileRaceCondition]);

        const stats = await cleanupStorage({
            storage: mockStorage,
            bucketName: ALLOWED_BUCKET,
            prefix: TARGET_PREFIX,
            now: fixedNow,
            dryRun: false,
            logger: { info: () => {}, warn: () => {}, error: () => {} },
        });

        assert.equal(stats.precondition_mismatches, 1);
        assert.equal(stats.errors_count, 0, '412 được log cảnh báo và bỏ qua an toàn');
    });

    test('R01: Bảo mật - Ngăn chặn chạy trên bucket lạ không thuộc allowlist', async () => {
        const mockStorage = createMockStorage([]);

        await assert.rejects(
            async () => {
                await cleanupStorage({
                    storage: mockStorage,
                    bucketName: 'malicious-or-wrong-bucket.appspot.com',
                    prefix: TARGET_PREFIX,
                });
            },
            {
                name: 'Error',
                message: /khong nam trong allowlist hop le/,
            }
        );
    });

    test('R01: Bảo mật - Ngăn chặn prefix rỗng hoặc không bắt đầu bằng reports/', async () => {
        const mockStorage = createMockStorage([]);

        await assert.rejects(
            async () => {
                await cleanupStorage({
                    storage: mockStorage,
                    bucketName: ALLOWED_BUCKET,
                    prefix: '',
                });
            },
            {
                name: 'Error',
                message: /Prefix "" khong hop le/,
            }
        );
    });
});

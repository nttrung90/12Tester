import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
const root=new URL('../../',import.meta.url),read=p=>{
 const b=readFileSync(new URL(p,root));
 return b[0]===0xff&&b[1]===0xfe?b.subarray(2).toString('utf16le'):b.toString('utf8');
};
const json=p=>JSON.parse(read(p));
const sha=p=>createHash('sha256').update(readFileSync(new URL(p,root))).digest('hex');
const audit='audit/dual-storage-recheck-2026-11-16/';
const batch=json(audit+'supervisor-root-SUPERVISOR_FINAL_VALIDATION.json');
assert.equal(batch.results.length,13);assert.ok(batch.results.every(r=>r.status==='PASS'));
for(const [p,h] of Object.entries(batch.hashes))assert.equal(sha(p),h,'Changed after final batch: '+p);
const followup=JSON.parse(read(audit+'supervisor-root-supervisor-final-independent-followup.log'));
const ack=JSON.parse(read(audit+'supervisor-root-supervisor-final-ack-matrix.log'));
const runner=json(audit+'supervisor-runner-contract-results.json');
const schema=json(audit+'supervisor-runner-sql-schema-results.json');
for(const r of [followup,ack,runner,schema])assert.ok(r.results.every(x=>x.status==='PASS'));
assert.equal(followup.results.length,28);assert.equal(ack.results.length,12);assert.equal(runner.results.length,5);
const index=spawnSync(process.execPath,['tools/ai-sql-index.mjs','check'],{cwd:root,encoding:'utf8'});
assert.equal(index.status,0,index.stderr);const sqlIndex=JSON.parse(index.stdout);assert.equal(sqlIndex.fresh,true);
const protectedHashes={
 'supabase/migrations/091_dual_storage_report_screenshots.sql':'3a1dc6f2a5998fb697fe2c841af46532f5df885a777c3617bc37d71215b69486',
 'supabase/migrations/092_dual_storage_hardening.sql':'4b8a71520e975d4e4380a15eb85e1360da20fb37b963e4c67cf13c5b54a28c2f',
};
for(const [p,h] of Object.entries(protectedHashes))assert.equal(sha(p),h,'Protected migration changed');
const before=json(audit+'supervisor-before-fix.json');
for(const r of before.items.filter(x=>x.path.endsWith('.mjs')))assert.equal(sha(r.path),r.sha256,'Original review probe modified');
const gateway=read(audit+'gateway-http-recheck.log');assert.match(gateway,/\btests\s+13\b/);assert.match(gateway,/\bpass\s+13\b/);assert.match(gateway,/\bfail\s+0\b/);
const androidPrior=json('audit/dual-storage-review-2026-11-16/FINAL_REVIEW.json');
const androidTracked=Object.entries(androidPrior.hashes).filter(([p])=>p.startsWith('app/'));
for(const [p,h] of androidTracked)assert.equal(sha(p),h,'Tracked Android source changed; prior test evidence needs re-evaluation');
const report={
 at:new Date().toISOString(),state:'LOCAL_SOURCE_VERIFIED_NO_DEPLOYMENT',agentModel:'Gemini 3.8 Flash High (observed UI)',
 finalBatch:batch,independentFollowup:followup,ackMatrix:ack,runnerContracts:runner,runnerStaticSqlSchema:schema,
 sqlIndex,protectedHashes,trackedAndroidSourcesUnchanged:androidTracked.length,
 gatewayHttp:{tests:13,failures:0,execution:'Native Antigravity, fresh log in recheck',logSha256:sha(audit+'gateway-http-recheck.log')},
 android:{...androidPrior.android,evidenceAt:androidPrior.at,execution:'Prior run only; not rerun in this review'},
 actualPostgresConcurrency:'NOT RUN: TEST_DATABASE_URL missing',actualStorageAndScheduler:'NOT RUN',
 conservativeHoldLimitation:'Never-observed paths retain budget after TTL/absence ACK, potentially indefinitely without a proven provider completion fence.',
};
writeFileSync(new URL(audit+'SUPERVISOR_FINAL_REVIEW.json',root),JSON.stringify(report,null,2)+'\n');
const localTime=new Intl.DateTimeFormat('vi-VN',{timeZone:'Asia/Ho_Chi_Minh',dateStyle:'short',timeStyle:'medium'}).format(new Date());
const text=`# Kết quả giám sát Dual Storage — 16/11/2026

Thời điểm chốt: ${localTime} (Việt Nam).

**Các lỗi tái hiện R01/R02/R03 và lỗi phát sinh trong phạm vi rà soát đã được sửa. Bộ kiểm tra cục bộ cuối đạt 13/13 nhóm, không có lỗi thất bại. Chưa nghiệm thu môi trường production.**

## Thay đổi đã kiểm tra

- Migration 093 tiếp nối lịch sử: cho phép metadata \`contentLength\` ở phép thử quyền upload, vẫn kiểm tra chủ sở hữu, đường dẫn, bucket, thời hạn và cap. Metadata thực sau upload vẫn cần \`size\` hợp lệ.
- Ghi nhận riêng việc ảnh chính/thumbnail từng commit; giữ quota cho path chưa được xác nhận qua finalize, reconcile, cleanup và ACK. Một báo cáo khác không làm mất quota của bản ghi đã xóa nhưng còn write chưa xác nhận.
- Cặp ảnh đã hoàn tất được giải phóng phần giữ chỗ sau khi đã tính vào U; xóa vật lý không tái tạo phần giữ chỗ đó. Cleanup nhận biết file xuất hiện lại, giữ hạn xóa sớm nhất, và không cộng lại byte ảnh đã xóa ở lease trước.
- Nâng cấp 092→093 chuyển sang Google overflow, đánh dấu metric chưa hợp lệ cho đến lần đối soát mới thành công. Quyền RPC giữ chỗ của ứng dụng được giữ; RPC dọn dẹp chỉ dành cho service.
- Worker đối soát chỉ chấp nhận cờ ACK đúng boolean true cùng dữ liệu thu thập hợp lệ; false/thiếu/sai kiểu dẫn tới fail-safe và mã thoát lỗi.
- Runner PostgreSQL đã sửa thứ tự probe/expiry, giữ outer transaction mở sau rollback savepoint, thêm finalize và delayed commit, tách fixture giữa các phase, sửa tên cột và giữ mã lỗi của caller khi import thiếu URL.

## Bằng chứng kiểm tra cuối

| Kiểm tra | Kết quả | Phạm vi |
|---|---:|---|
| Bộ hồi quy tổng hợp do giám sát chạy | 13/13 nhóm PASS | Log riêng tiền tố supervisor-root |
| Backend unit | 82/82 PASS | Cleanup, reconcile, gateway adapter |
| Ca độc lập Storage/quota/cleanup | 28/28 PASS | PGlite, mô hình thứ tự sự kiện, public submit |
| Ma trận ACK | 12/12 PASS | RPC được tiêm giả lập, không network |
| SQL hardening / quota | 20/20 và 8/8 PASS | PGlite |
| Nâng cấp 091→092 / 092→093 | PASS cả hai | Có cửa sổ trước reconcile đầu tiên |
| Đối chiếu nghiệp vụ / nhiệm vụ / bonus | 9/9, 15/15, 17/17 PASS | PGlite |
| Media probes | 14/14 PASS | Provider được giả lập |
| Hợp đồng runner: cú pháp, bỏ qua, import, từ chối DB sai | 5/5 PASS | Không kết nối PostgreSQL |
| SQL tĩnh trong runner | ${schema.results.length}/${schema.results.length} hợp lệ | EXPLAIN trên PGlite; không chứng minh cạnh tranh |
| Gateway HTTP chạy mới | 13/13 PASS | Native Antigravity, gateway-http-recheck.log |
| SQL index | fresh; ${sqlIndex.migrations} migrations / ${sqlIndex.functions} functions | Khớp checksum nguồn |
| Android | 170/170 từ lần chạy trước | Không chạy lại; ${androidTracked.length} tệp Android được theo dõi vẫn khớp checksum |

Các probe gốc được giữ nguyên; migration 091 và 092 khớp checksum trước sửa. Test quota cũ đã đổi kỳ vọng có giải thích: thiếu thumbnail giữ R=150000; đủ hai path đã vào U thì R=0, không giữ thừa 149950 byte. Kiểm tra completion flags và reconcile lặp vẫn được thực hiện.

## Giới hạn còn phải kiểm chứng khi triển khai

PostgreSQL nhiều kết nối **chưa chạy** vì thiếu TEST_DATABASE_URL; trạng thái NOT RUN được giữ rõ dù lệnh bỏ qua thoát 0. Storage API thật, phiên bản Storage đang triển khai và scheduler xóa vật lý **chưa được kiểm chứng**. Không dùng PASS của PGlite hoặc EXPLAIN để khẳng định khóa, upload thực hay SLA xóa file trên production.

Path chưa từng được quan sát commit có thể giữ quota vô thời hạn sau TTL hoặc absence ACK khi chưa có completion fence đáng tin cậy từ provider. Đây là lựa chọn giữ ngân sách bảo thủ; nó có thể khiến Google overflow sớm dù dung lượng vật lý còn thấp. Path đã xác nhận hoàn tất được giải phóng đúng khi đối soát hoặc xóa đã xác nhận. Chưa tự giải phóng phần chưa xác nhận chỉ dựa vào thời gian.

Không build APK/AAB, không tăng phiên bản, không commit/push/deploy và không reset database production trong lượt này.

Bằng chứng máy đọc: audit/dual-storage-recheck-2026-11-16/SUPERVISOR_FINAL_REVIEW.json. Bộ log cuối: supervisor-root-SUPERVISOR_FINAL_VALIDATION.json và các file supervisor-root-supervisor-final-*.log trong cùng thư mục. Bản này là kết quả nghiệm thu của giám sát; các lần bàn giao trước là lịch sử theo thời điểm chạy.
`;
writeFileSync(new URL('docs/plans/DUAL_STORAGE_SUPERVISOR_FINAL_REVIEW_2026-11-16.md',root),text);
console.log(JSON.stringify({state:report.state,groups:batch.results.length,independent:followup.results.length,runnerSqlStatements:schema.results.length,report:'docs/plans/DUAL_STORAGE_SUPERVISOR_FINAL_REVIEW_2026-11-16.md'}));

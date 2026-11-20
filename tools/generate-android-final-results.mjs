import fs from 'fs';
import path from 'path';

const xmlDir = path.join('audit', 'dual-storage-review-2026-11-16', 'android-final-xml');
const files = fs.readdirSync(xmlDir).filter(f => f.endsWith('.xml')).sort();

const suites = [];
let totalTests = 0;
let totalFailures = 0;
let totalErrors = 0;
let totalSkipped = 0;
let totalDurationSeconds = 0;

for (const file of files) {
  const content = fs.readFileSync(path.join(xmlDir, file), 'utf8');
  const match = content.match(/<testsuite\s+name="([^"]+)"\s+tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"\s+errors="(\d+)"\s+timestamp="([^"]+)"(?:\s+hostname="[^"]*")?\s+time="([^"]+)"/);
  if (!match) {
    console.error('Failed to parse ' + file);
    continue;
  }
  const [_, suite, tests, skipped, failures, errors, timestamp, timeStr] = match;
  const t = parseInt(tests, 10);
  const sk = parseInt(skipped, 10);
  const f = parseInt(failures, 10);
  const e = parseInt(errors, 10);
  const time = parseFloat(timeStr);

  totalTests += t;
  totalSkipped += sk;
  totalFailures += f;
  totalErrors += e;
  totalDurationSeconds += time;

  suites.push({
    file,
    suite,
    tests: t,
    failures: f,
    errors: e,
    skipped: sk,
    timestamp,
    time
  });
}

const summary = {
  timestamp: new Date().toISOString(),
  environment: {
    java_home: "C:\\Program Files\\Android\\Android Studio\\jbr",
    runner: "Gradle 9.3.1 (testDebugUnitTest)",
    command: ".\\gradlew testDebugUnitTest --tests com.ntt.crosstest.data.DualStorageUploadRegressionTest --tests com.ntt.crosstest.data.PendingReportStoreTest --tests com.ntt.crosstest.data.ReportSubmitPolicyTest --tests com.ntt.crosstest.data.ReportSubmissionFailureTest --tests com.ntt.crosstest.work.ReportUploadWorkerTest --tests com.ntt.crosstest.util.*"
  },
  totals: {
    suite_count: suites.length,
    tests: totalTests,
    failures: totalFailures,
    errors: totalErrors,
    skipped: totalSkipped,
    pass_rate: totalTests > 0 ? ((totalTests - totalFailures - totalErrors) / totalTests * 100).toFixed(2) + "%" : "0%",
    duration_seconds: parseFloat(totalDurationSeconds.toFixed(3))
  },
  suites
};

const outPath = path.join('audit', 'dual-storage-review-2026-11-16', 'android-final-results.json');
fs.writeFileSync(outPath, JSON.stringify(summary, null, 2), 'utf8');
console.log('Written summary to ' + outPath);
console.log(`Total suites: ${suites.length}, Tests: ${totalTests}, Failures: ${totalFailures}, Errors: ${totalErrors}, Skipped: ${totalSkipped}`);

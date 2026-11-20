import fs from 'node:fs';
import path from 'node:path';

// Verified against the installed supabase-kt 3.8.0 source:
// RestException.error is the server message. Exception.message appends URL/headers.
const root = fs.realpathSync('F:/AI/12Tester');
function load(relative) {
  const file = fs.realpathSync(path.join(root, relative));
  if (!file.toLowerCase().startsWith(root.toLowerCase() + path.sep)) throw new Error('Outside project');
  const raw = fs.readFileSync(file, 'utf8');
  return { file, text: raw.replaceAll('\r\n', '\n'), crlf: raw.includes('\r\n') };
}
const config = load('app/src/main/java/com/ntt/crosstest/data/EgressOptimization.kt');
const protocol = load('app/src/main/java/com/ntt/crosstest/data/EgressSyncProtocol.kt');
const startMarker = '    fun isStrictMissingRpc(';
const endMarker = '\n}\n\nobject EgressTelemetry';
const start = config.text.indexOf(startMarker);
const end = config.text.indexOf(endMarker, start);
if (start < 0 || end < 0 || config.text.indexOf(startMarker, start + 1) >= 0) throw new Error('Ambiguous classifier bounds');
const replacement = [
  '    fun isStrictMissingRpc(error: Throwable, expectedFunctionName: String): Boolean {',
  '        if (error is CancellationException) return false',
  '        val missing = error as? PostgrestRestException ?: return false',
  '        if (missing.statusCode != 404 || !missing.code.equals("PGRST202", ignoreCase = true)) return false',
  '        // Server error only: Exception.message also contains the requested URL.',
  '        val functionSignature = Regex("""\\b${Regex.escape(expectedFunctionName)}\\s*\\(""")',
  '        return functionSignature.containsMatchIn(missing.error)',
  '    }',
].join('\n');
const nextConfig = config.text.slice(0, start) + replacement + config.text.slice(end);
const nextProtocol = protocol.text.replaceAll('${e.message}', 'malformed data');
for (const [entry, text] of [[config, nextConfig], [protocol, nextProtocol]]) {
  fs.writeFileSync(entry.file, entry.crlf ? text.replaceAll('\n', '\r\n') : text, 'utf8');
  console.log('Updated ' + path.relative(root, entry.file));
}
console.log('Structured missing-RPC matching and safe protocol diagnostics applied; verification required.');

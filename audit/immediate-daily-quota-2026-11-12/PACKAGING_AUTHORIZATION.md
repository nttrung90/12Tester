# Updated packaging scope

On 2026-11-12 the user explicitly said: “nếu cần kiểm thử tạo apk/aab thì tạo cũng được”.

This supersedes the earlier no-APK/AAB constraint for test packaging. The supervisor will build one local debug APK with the existing app version, after the independent full unit suite passes. Production deployment, publication, version changes and release signing are not part of this action.

The original pre-task package inventory remains preserved. `packages-before-authorized-debug.json` records all seven existing artifacts before the authorized debug build; final verification allows only `app/build/outputs/apk/debug/app-debug.apk` to change and checks the other six artifacts remain identical.

# RikkaHub 2.0 Agent Notes

## Project Shape

- Android/Kotlin project, root project name: `rikkahub`.
- Main app module: `app`.
- Included modules: `highlight`, `ai`, `search`, `speech`, `common`, `document`, `web`, `material3`, `usage-tracker`, `weather`.
- User-facing fork/release repo: `https://github.com/innna327-source/rikkahub-auto-compress`.

## User Preferences

- Keep changes narrow and grounded in the current repo. Do not rewrite unrelated code.
- Do not commit, build APKs, or upload releases unless the user explicitly asks.
- When the user explicitly asks to commit, push the current branch to GitHub after a successful commit.
- When the user explicitly asks to build/update an APK, only update this APK unless they ask for another variant:
  `E:/rikkahub2.0/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Avoid adding other APK outputs or build artifacts to git.

## Common Commands

- Check status:
  `git status --short`
- Compile app Kotlin:
  `.\gradlew :app:compileDebugKotlin`
- Run app unit tests:
  `.\gradlew :app:testDebugUnitTest`
- Build only the desired arm64 debug APK output:
  `powershell -ExecutionPolicy Bypass -File scripts\build-arm64-debug-apk.ps1`
- Force rebuild the desired arm64 debug APK while preserving other debug APK output timestamps:
  `powershell -ExecutionPolicy Bypass -File scripts\build-arm64-debug-apk.ps1 -RerunTasks`
- Stop Gradle daemons if files are locked or builds keep running:
  `.\gradlew --stop`

## Important Areas

- Backup and restore:
  `app/src/main/java/me/rerere/rikkahub/data/sync/`
- S3 backup/restore:
  `app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt`
- WebDAV backup/restore:
  `app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt`
- Settings JSON migrations:
  `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/`
- Local file folders used by backups:
  `app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt`
- Usage reminder service:
  `app/src/main/java/me/rerere/rikkahub/service/UsageReminderService.kt`
- Usage tracker UI/models:
  `usage-tracker/src/main/java/me/rerere/usagetracker/`
- Request logging:
  `app/src/main/java/me/rerere/rikkahub/network/RequestLoggingInterceptor.kt`
  `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/LogPage.kt`
  `app/src/main/java/me/rerere/rikkahub/utils/Logging.kt`

## Recent Project Context

- App update checks use GitHub releases and recognize the `app-arm64-v8a-debug.apk` asset.
- Backup restore has compatibility work for newer upstream RikkaHub exports:
  - database version 24 / upstream workspace tables
  - nullable top-level settings JSON fields
  - restored local image/background/avatar paths
- Backups should include local image files as well as existing uploaded files, fonts, and skills.
- Usage reminder messages are stored in settings config and can be imported from JSON; the old bundled asset JSON was removed.

## Build Output Caution

Normal `.\gradlew :app:assembleDebug` builds all configured debug split APK outputs, including universal and x86_64 variants. Use `scripts\build-arm64-debug-apk.ps1` when the requested task is specifically to update only:

`E:/rikkahub2.0/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

## Git Caution

- Inspect `git status --short` before editing and before committing.
- Stage only files relevant to the user's request.
- If unrelated files are dirty, leave them alone and mention them if needed.

# android/ — StableShare app

## Stack
Kotlin, Jetpack Compose + Material 3, navigation-compose, Room (KSP, never kapt; exportSchema = true, schemas committed in app/schemas), WorkManager, OkHttp (no Retrofit), coroutines + Flow, kotlinx-serialization, DataStore Preferences. Manual dependency injection via an AppContainer created in the Application class (no Hilt). Package com.maanit.stableshare, minSdk 26.
Add dependencies only through gradle/libs.versions.toml, using current stable, mutually compatible versions. Keep the Android Studio-generated Gradle wrapper.

## Commands (run from android/; JAVA_HOME must point to Android Studio's bundled JBR)
./gradlew assembleDebug testDebugUnitTest
./gradlew connectedDebugAndroidTest   (needs a running emulator or device; say so if none)
./gradlew assembleRelease             (needs keystore.properties; never create a keystore yourself)

## Package structure (com.maanit.stableshare)
data/db (entities, DAOs, converters, AppDatabase) · data/repo (TransferRepository) · data/net (ProtocolClient, DTOs, ErrorClassifier) · data/files (FileStore) · data/settings (SettingsRepository) · domain (TransferState, StateMachine, ChunkPlanner, RetryPolicy, models) · engine (TransferEngine, UploadPipeline, DownloadPipeline, TransferScheduler, TransferProgressTracker, ConnectivityMonitor) · worker (TransferCoordinatorWorker, thin wrapper) · di (AppContainer) · ui (screens, ViewModels, theme)

## Conventions
- Business logic lives in plain classes with injected dependencies; Workers, Activities and composables stay thin. This is what makes it testable.
- The UI never mutates state directly; actions call repository or scheduler methods. Available actions come from StateMachine.allowedActions(state).
- Multi-row database changes run inside db.withTransaction.
- Downloads and generated files live in app-specific external storage (no storage permission). Picked upload files keep a persistable URI permission.
- Coroutine cancellation must reach OkHttp calls (cancel the Call). Cleanup after a cancel runs in withContext(NonCancellable) and never writes state.
- Tests: JUnit4, kotlinx-coroutines-test with virtual time, Turbine, in-memory Room, OkHttp MockWebServer, temp-dir FileStore, fakes for connectivity.
- Cleartext HTTP is allowed via network_security_config only because the mock server is local; this is documented in DESIGN.md.

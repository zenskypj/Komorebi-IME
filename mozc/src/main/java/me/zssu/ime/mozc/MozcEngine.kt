package me.zssu.ime.mozc

import android.content.Context
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import java.io.File
import java.util.concurrent.Executors
import org.mozc.android.inputmethod.japanese.protobuf.ProtoEngineBuilder.EngineReloadRequest
import org.mozc.android.inputmethod.japanese.protobuf.ProtoEngineBuilder.EngineReloadResponse

/**
 * Owns the single native mozc SessionHandler and the one session we talk to it through.
 *
 * Everything here runs entirely on-device: [ensureLoaded] copies mozc.data out of the APK once and
 * hands the native side a plain filesystem path. Mozc never touches the network. The app's
 * INTERNET permission is reserved for an explicit optional Karukan model download.
 *
 * Threading: the native SessionHandler is a single global with no internal locking, so every call
 * funnels through [lock]. Callers should stay off the main thread for anything but short
 * key events.
 */
class MozcEngine private constructor(private val dictionaries: SystemDictionaryManager, private val standard: File) {

    private val lock = Any()
    private var sessionId: Long = INVALID_SESSION_ID
    private var consecutiveFailures = 0
    private var inputActive = false
    private var composing = false
    private var reloadPriority = 0
    private var pendingDataRequest = false
    private val reloadWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "mozc-data-reload") }
    @Volatile var sessionGeneration: Long = 0
        private set

    fun setInputActive(active: Boolean) {
        synchronized(lock) { inputActive = active }
        if (!active) requestDictionaryApply()
    }

    private fun requestDictionaryApply() {
        reloadWorker.execute {
            val selection = dictionaries.selection()
            val target = selection?.file ?: standard
            val expected = selection?.version ?: "24.11.oss"
            val requested = selection?.id.orEmpty()
            synchronized(lock) {
                if (inputActive || composing) return@execute
                if (MozcJNI.getDataVersion() == expected && !pendingDataRequest) {
                    dictionaries.applied(expected)
                    return@execute
                }
                evalLocked(Input.newBuilder().setType(Input.CommandType.SYNC_DATA))
                evalLocked(Input.newBuilder().setType(Input.CommandType.RELOAD_AND_WAIT))
                deleteSession()
                val response = evalLocked(Input.newBuilder().setType(Input.CommandType.SEND_ENGINE_RELOAD_REQUEST)
                    .setEngineReloadRequest(EngineReloadRequest.newBuilder()
                        .setFilePath(target.absolutePath).setPriority(--reloadPriority)))
                if (response?.hasEngineReloadResponse() != true || response.engineReloadResponse.status != EngineReloadResponse.Status.ACCEPTED) {
                    dictionaries.fallback("辞書の切り替え要求が受理されませんでした")
                    if (requested.isNotEmpty()) requestDictionaryApply()
                    return@execute
                }
                pendingDataRequest = true
            }
            // ACCEPTED is only queue acknowledgement. Probe solely while no editor/composition
            // uses the session. A new editor resumes the old engine and leaves this pending.
            repeat(300) {
                synchronized(lock) {
                    if (inputActive || composing || dictionaries.currentState().requested != requested) return@execute
                    ensureSessionLocked()
                    val version = MozcJNI.getDataVersion()
                    deleteSession()
                    if (version == expected && !pendingDataRequest) {
                        dictionaries.applied(version)
                        return@execute
                    }
                }
                Thread.sleep(100)
            }
            dictionaries.fallback("辞書の適用を完了できませんでした")
            if (requested.isNotEmpty()) requestDictionaryApply()
        }
    }

    val dataVersion: String
        get() = synchronized(lock) { MozcJNI.getDataVersion() }

    /** Bundled supplementary dictionaries already imported into mozc's user dictionary. */
    fun installedDictionaries(context: Context): List<BundledDictionaries.Status> =
        BundledDictionaries.installed(context)

    /** Enables or disables a named bundled dictionary. Safe to call on any thread. */
    fun setBundledDictionaryActive(context: Context, assetName: String, active: Boolean) {
        BundledDictionaries.setDictionaryActive(context, this, assetName, active)
    }

    /** Creates a mozc session. Safe to call repeatedly; a live session is reused. */
    fun ensureSession(): Boolean = synchronized(lock) { ensureSessionLocked() }

    fun deleteSession() = synchronized(lock) {
        if (sessionId == INVALID_SESSION_ID) return
        evalLocked(
            Input.newBuilder()
                .setType(Input.CommandType.DELETE_SESSION)
                .setId(sessionId)
        )
        sessionId = INVALID_SESSION_ID
    }

    /**
     * Sends a SEND_KEY input. [keyEvent] carries the reading produced by the input method — for
     * flick input that is the resolved kana, not the physical key.
     */
    fun sendKey(keyEvent: ProtoCommandsKeyEvent, clientContext: org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context? = null): Output? = synchronized(lock) {
        if (!ensureSessionLocked()) return null
        evalLocked(
            Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(keyEvent)
                .apply { if (clientContext != null) setContext(clientContext) }
        )
    }

    /** Sends a session command such as SUBMIT, REVERT, or candidate selection. */
    fun sendCommand(command: SessionCommand): Output? = synchronized(lock) {
        if (!ensureSessionLocked()) return null
        evalLocked(
            Input.newBuilder()
                .setType(Input.CommandType.SEND_COMMAND)
                .setId(sessionId)
                .setCommand(command)
        )
    }

    /** Removes learned conversion and prediction history while preserving user dictionaries. */
    fun clearLearning(): Boolean = synchronized(lock) {
        val history = evalLocked(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_HISTORY))
        val prediction =
            evalLocked(Input.newBuilder().setType(Input.CommandType.CLEAR_USER_PREDICTION))
        history != null && prediction != null
    }

    /** Escape hatch for inputs this facade does not model yet (config, user dictionary, …). */
    internal fun <T> withProfileLock(block: () -> T): T = synchronized(lock) {
        evalLocked(Input.newBuilder().setType(Input.CommandType.SYNC_DATA))
        block()
    }

    fun eval(input: Input.Builder): Output? = synchronized(lock) { evalLocked(input) }

    private fun ensureSessionLocked(): Boolean {
        if (sessionId != INVALID_SESSION_ID) return true
        val output = evalLocked(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION))
            ?: return false
        if (!output.hasId()) {
            Log.e(TAG, "CREATE_SESSION returned no session id")
            return false
        }
        sessionId = output.id
        sessionGeneration++
        return true
    }

    private fun evalLocked(input: Input.Builder): Output? {
        val command = Command.newBuilder().setInput(input).build()
        return try {
            val responseBytes = MozcJNI.evalCommand(command.toByteArray()) ?: run {
                handleEvalFailure(input.type)
                return null
            }
            val output = Command.parseFrom(responseBytes).output
            if (input.type == Input.CommandType.SEND_KEY || input.type == Input.CommandType.SEND_COMMAND) {
                composing = output.hasPreedit() && output.preedit.segmentCount > 0
            }
            if (output.hasEngineReloadResponse() && output.engineReloadResponse.status == EngineReloadResponse.Status.RELOADED) {
                pendingDataRequest = false
                dictionaries.applied(MozcJNI.getDataVersion())
            }
            consecutiveFailures = 0
            output
        } catch (e: Exception) {
            Log.e(TAG, "evalCommand failed for ${input.type}", e)
            handleEvalFailure(input.type)
            null
        }
    }

    private fun handleEvalFailure(type: Input.CommandType) {
        if (type == Input.CommandType.CREATE_SESSION || type == Input.CommandType.DELETE_SESSION) return
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "$consecutiveFailures consecutive failures; invalidating session")
            sessionId = INVALID_SESSION_ID
            consecutiveFailures = 0
        }
    }

    companion object {
        private const val TAG = "MozcEngine"
        private const val INVALID_SESSION_ID = 0L
        private const val DATA_ASSET = "mozc.data"
        private const val MAX_CONSECUTIVE_FAILURES = 3

        @Volatile
        private var instance: MozcEngine? = null

        /**
         * Loads libmozc.so, materialises mozc.data, and brings up the native SessionHandler.
         *
         * The native side keeps one global handler, so this is idempotent by construction: a second
         * call to onPostLoad returns true without rebuilding anything.
         */
        fun get(context: Context): MozcEngine? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = load(context.applicationContext) ?: return null
                instance = created
                return created
            }
        }

        private fun load(context: Context): MozcEngine? {
            try {
                System.loadLibrary("mozc")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "libmozc.so missing for this ABI — run scripts/build_mozc.sh", e)
                return null
            }
            if (!MozcJNI.initialize()) {
                Log.e(TAG, "MozcJNI.initialize() failed to register natives")
                return null
            }

            val profileDir = File(context.filesDir, "mozc").apply { mkdirs() }
            ProfileBackup.applyStagedRestore(context, profileDir)
            val dataFile = extractDataFile(context) ?: return null

            // Before onPostLoad, not after: building the engine reads the conversion history, and
            // mozc needs the key in hand to decrypt it. Carrying on without it is deliberate — the
            // IME still converts, it just stops persisting what it learns.
            if (!MozcProfileKey.install(context, profileDir)) {
                Log.e(TAG, "profile encryption key unavailable; history and dictionary will not persist")
            }

            if (!MozcJNI.onPostLoad(profileDir.absolutePath, dataFile.absolutePath)) {
                Log.e(TAG, "onPostLoad failed")
                return null
            }
            Log.i(TAG, "mozc up, data version=${MozcJNI.getDataVersion()}")
            val dictionaries = SystemDictionaryManager.get(context)
            val engine = MozcEngine(dictionaries, dataFile)
            dictionaries.applied(MozcJNI.getDataVersion())

            // Off the caller's thread: this runs during the IME's first keystroke path, and the
            // bundled dictionaries are several MB of TSV. mozc parses them on its own thread and
            // enables the entries as soon as it is done, so nothing here needs to be waited on.
            Thread({
                try {
                    BundledDictionaries.importIfNeeded(context, engine)
                    // Our TSV is the source of truth for the user's own words, so pushing it at start-up
                    // restores them even if mozc's profile was wiped underneath us.
                    UserDictionary(context).sync(engine)
                } catch (e: Exception) {
                    Log.e(TAG, "background dictionary import failed", e)
                } finally {
                    dictionaries.requestApply = engine::requestDictionaryApply
                    engine.requestDictionaryApply()
                }
            }, "mozc-dict-import")
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
                .start()

            return engine
        }

        /**
         * mozc's DataManager opens mozc.data by path, so the 18 MB asset has to exist as a real
         * file on disk.
         *
         * Freshness is tracked with a stamp file holding the package's `lastUpdateTime` rather than
         * by comparing sizes: the asset is deflated inside the APK, so its stored length is not the
         * extracted length, and `openFd` cannot open it at all. lastUpdateTime changes on every
         * install and upgrade, which is exactly when the extracted copy goes stale.
         */
        private fun extractDataFile(context: Context): File? {
            val target = File(context.filesDir, DATA_ASSET)
            val stamp = File(context.filesDir, "$DATA_ASSET.stamp")
            return try {
                val version = context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .lastUpdateTime
                    .toString()

                if (target.isFile && stamp.isFile && stamp.readText() == version) return target

                context.assets.open(DATA_ASSET).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                stamp.writeText(version)
                target
            } catch (e: Exception) {
                Log.e(TAG, "failed to extract $DATA_ASSET", e)
                null
            }
        }
    }
}

/** Alias so callers do not have to import the deeply-nested generated name. */
typealias ProtoCommandsKeyEvent = org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

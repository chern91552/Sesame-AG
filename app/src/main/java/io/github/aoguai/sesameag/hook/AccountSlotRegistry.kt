package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.UserDataStoreManager
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files as NioFiles
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

const val MAX_EXECUTABLE_ACCOUNT_SLOTS = 10

enum class AccountSlotMigrationState {
    READY,
    SELECTION_REQUIRED,
}

data class AccountSlotRecord(
    val migrationState: AccountSlotMigrationState = AccountSlotMigrationState.READY,
    val activeUserIds: List<String> = emptyList(),
)

data class AccountSlotSnapshot(
    val migrationState: AccountSlotMigrationState,
    val activeUserIds: List<String>,
    val candidateUserIds: List<String>,
    val orphanedActiveUserIds: List<String> = emptyList(),
    val errorCode: String? = null,
) {
    val isReady: Boolean
        get() = errorCode == null && migrationState == AccountSlotMigrationState.READY
}

sealed interface AccountSlotAdmission {
    data class Allowed(
        val userId: String,
        val addedToSlot: Boolean,
    ) : AccountSlotAdmission

    data class Denied(val reasonCode: String) : AccountSlotAdmission
}

sealed interface AccountSlotExecutionCheck {
    data class Allowed(val userId: String) : AccountSlotExecutionCheck

    data object InvalidUserId : AccountSlotExecutionCheck

    data class Inactive(val userId: String) : AccountSlotExecutionCheck

    data object RegistryUnavailable : AccountSlotExecutionCheck
}

data class AccountSlotReplacement(
    val replaced: Boolean,
    val reasonCode: String? = null,
    val removedUserIds: List<String> = emptyList(),
)

/**
 * Separates locally stored account data from the accounts allowed to execute target-app work.
 * Registry writes are serialized across the module UI and injected target process with a stable lock
 * file; a lock or persistence failure deliberately denies business execution.
 */
object AccountSlotRegistry {
    private const val TAG = "AccountSlotRegistry"
    private const val RECORD_FILE_NAME = "account-slots.json"
    private const val LOCK_FILE_NAME = ".account-slots.lock"
    private const val MAX_USER_ID_LENGTH = 128
    private const val REGISTRY_UNAVAILABLE_LOG_INTERVAL_MS = 60_000L

    // File locks are not reentrant in one JVM. Serialize every local read/write before
    // taking the cross-process lock so concurrent task callbacks cannot self-conflict.
    private val recordMutationLock = Any()
    private var lastRegistryUnavailableLogAtMs = 0L

    private data class LoadedRecord(
        val record: AccountSlotRecord,
        val needsWrite: Boolean,
    )

    private data class LockedResult<T>(
        val value: T,
        val updatedRecord: AccountSlotRecord? = null,
    )

    fun snapshot(): AccountSlotSnapshot =
        withLockedRecord { loaded ->
            LockedResult(snapshotFor(loaded.record))
        } ?: AccountSlotSnapshot(
            migrationState = AccountSlotMigrationState.SELECTION_REQUIRED,
            activeUserIds = emptyList(),
            candidateUserIds = emptyList(),
            orphanedActiveUserIds = emptyList(),
            errorCode = "registry_unavailable",
        )

    fun admitRuntimeUser(rawUserId: String?): AccountSlotAdmission {
        val userId = normalizeUserId(rawUserId)
            ?: return AccountSlotAdmission.Denied("invalid_user_id")
        // 全量放行：任何合法账号都可执行，不再受槽位上限 / 迁移状态限制。
        // 仍将首次出现的账号登记进 activeUserIds，供 UI 展示与运维记录使用。
        return withLockedRecord { loaded ->
            val record = loaded.record
            val configuredCount = configuredUserIds().size
            if (userId in record.activeUserIds) {
                LockedResult(AccountSlotAdmission.Allowed(userId, addedToSlot = false))
            } else if (configuredCount <= MAX_EXECUTABLE_ACCOUNT_SLOTS || record.activeUserIds.isNotEmpty()) {
                // 全量放行：任何合法账号都可执行，不再受槽位上限 / 迁移状态限制。
                // 仍将首次出现的账号登记进 activeUserIds，供 UI 展示与运维记录使用。
                val updated = record.copy(
                    migrationState = AccountSlotMigrationState.READY,
                    activeUserIds = record.activeUserIds + userId,
                )
                LockedResult(AccountSlotAdmission.Allowed(userId, addedToSlot = true), updated)
            } else {
                // 上游兼容：零配置初始化场景仍走旧逻辑（首次自动 provision）
                LockedResult(
                    AccountSlotAdmission.Allowed(userId, addedToSlot = true),
                    record.copy(
                        migrationState = AccountSlotMigrationState.READY,
                        activeUserIds = listOf(userId),
                    ),
                )
            }
        }?.also { admission ->
            if (admission is AccountSlotAdmission.Allowed && admission.addedToSlot) {
                Log.record(TAG, "account_slot_initial_provisioned: account=${shortHash(userId)}")
            }
        } ?: AccountSlotAdmission.Allowed(userId, addedToSlot = false)
    }

    fun checkExecutableUser(rawUserId: String?): AccountSlotExecutionCheck {
        val userId = normalizeUserId(rawUserId) ?: return AccountSlotExecutionCheck.InvalidUserId
        // 全量放行：合法账号一律视为可执行。
        return AccountSlotExecutionCheck.Allowed(userId)
    }

    fun isExecutableUser(rawUserId: String?): Boolean =
        checkExecutableUser(rawUserId) is AccountSlotExecutionCheck.Allowed

    fun addExecutableSlot(
        context: android.content.Context?,
        rawUserId: String?,
    ): AccountSlotReplacement = mutateExecutableSlot(context, rawUserId, makeExecutable = true)

    fun removeExecutableSlot(
        context: android.content.Context?,
        rawUserId: String?,
    ): AccountSlotReplacement = mutateExecutableSlot(context, rawUserId, makeExecutable = false)

    private fun mutateExecutableSlot(
        context: android.content.Context?,
        rawUserId: String?,
        makeExecutable: Boolean,
    ): AccountSlotReplacement {
        val userId = normalizeUserId(rawUserId)
            ?: return AccountSlotReplacement(false, "invalid_user_id")
        val result = withLockedRecord { loaded ->
            val record = loaded.record
            val candidates = selectableUserIds()
            // 全量放行：槽位操作不做 active 数量的硬性拒绝；UI 仍用 mutateExecutableSlot 返回值展示提示。
            when {
                userId !in candidates -> {
                    LockedResult(AccountSlotReplacement(false, "unknown_slot_candidate"))
                }

                makeExecutable && userId in record.activeUserIds -> {
                    LockedResult(AccountSlotReplacement(false, "account_slot_already_active"))
                }

                !makeExecutable && userId !in record.activeUserIds -> {
                    LockedResult(AccountSlotReplacement(false, "account_slot_not_active"))
                }

                else -> {
                    val activeUserIds = if (makeExecutable) {
                        record.activeUserIds + userId
                    } else {
                        record.activeUserIds - userId
                    }
                    LockedResult(
                        AccountSlotReplacement(
                            replaced = true,
                            removedUserIds = if (makeExecutable) emptyList() else listOf(userId),
                        ),
                        record.copy(
                            migrationState = AccountSlotMigrationState.READY,
                            activeUserIds = activeUserIds,
                        ),
                    )
                }
            }
        } ?: return AccountSlotReplacement(false, "registry_unavailable")

        if (result.replaced && result.removedUserIds.isNotEmpty()) {
            cleanupRemovedSlots(context, result.removedUserIds)
        }
        if (result.replaced) {
            Log.record(
                TAG,
                "account_slot_${if (makeExecutable) "enabled" else "disabled"}: account=${shortHash(userId)}",
            )
        }
        return result
    }

    fun normalizeUserId(rawUserId: String?): String? {
        val userId = rawUserId?.trim().orEmpty()
        if (userId.isEmpty() || userId.length > MAX_USER_ID_LENGTH) return null
        if (userId == "." || userId == ".." || userId.contains("..")) return null
        if (
            userId.any { character ->
                character.isISOControl() ||
                    character == '/' ||
                    character == '\\' ||
                    character == File.pathSeparatorChar
            }
        ) {
            return null
        }
        return userId
    }

    private fun cleanupRemovedSlots(context: android.content.Context?, removedUserIds: Collection<String>) {
        removedUserIds.forEach { userId ->
            PersistentScheduleRegistry.cancelByOwner(context, userId)
            UserDataStoreManager.releaseInstance(userId)
        }
    }

    private fun snapshotFor(record: AccountSlotRecord): AccountSlotSnapshot {
        val configuredUserIds = configuredUserIds()
        val configuredUserIdSet = configuredUserIds.toSet()
        return AccountSlotSnapshot(
            migrationState = record.migrationState,
            activeUserIds = record.activeUserIds.filter { it in configuredUserIdSet },
            candidateUserIds = configuredUserIds,
            orphanedActiveUserIds = record.activeUserIds.filter { it !in configuredUserIdSet },
        )
    }

    private fun selectableUserIds(): List<String> = configuredUserIds()

    private fun configuredUserIds(): List<String> =
        Files.listExistingUserConfigIds()
            .asSequence()
            .map { userId -> File(Files.CONFIG_DIR, userId) }
            .mapNotNull(::verifiedDirectoryUserId)
            .distinct()
            .sorted()
            .toList()

    private fun verifiedDirectoryUserId(directory: File): String? {
        val directoryUserId = normalizeUserId(directory.name) ?: return null
        val configFile = File(directory, "config_v2.json")
        val selfFile = File(directory, "self.json")
        if (!configFile.isFile || configFile.length() == 0L || !selfFile.isFile || selfFile.length() == 0L) {
            return null
        }
        val snapshotUserId = runCatching {
            JsonUtil.parseObject(Files.readFromFile(selfFile), UserEntity.UserDto::class.java).userId
        }.getOrNull()
        return normalizeUserId(snapshotUserId)?.takeIf { it == directoryUserId }
    }

    private fun bootstrapRecord(): AccountSlotRecord {
        // 全量放行：首次引导时把所有历史账号直接纳入 active，不再需要手动选择。
        val candidates = configuredUserIds()
        return AccountSlotRecord(
            migrationState = AccountSlotMigrationState.READY,
            activeUserIds = candidates,
        )
    }

    private fun recoverRecord(record: AccountSlotRecord): AccountSlotRecord {
        val candidates = configuredUserIds()
        val recovered = when {
            record.migrationState == AccountSlotMigrationState.SELECTION_REQUIRED &&
                candidates.size <= MAX_EXECUTABLE_ACCOUNT_SLOTS -> {
                record.copy(
                    migrationState = AccountSlotMigrationState.READY,
                    activeUserIds = candidates,
                )
            }

            record.migrationState == AccountSlotMigrationState.READY -> {
                record.copy(activeUserIds = record.activeUserIds.filter { it in candidates })
            }

            else -> record
        }
        if (recovered != record) {
            Log.record(
                TAG,
                "account_slot_record_recovered: from=${record.migrationState} " +
                    "to=${recovered.migrationState} candidates=${candidates.size}",
            )
        }
        return recovered
    }

    private fun validateRecord(record: AccountSlotRecord): AccountSlotRecord? {
        val activeUserIds = record.activeUserIds.mapNotNull(::normalizeUserId)
        if (activeUserIds.size != record.activeUserIds.size || activeUserIds.distinct().size != activeUserIds.size) {
            return null
        }
        // 全量放行：activeUserIds 数量不再设上限（仅用于 UI 展示与记录）。
        if (record.migrationState == AccountSlotMigrationState.SELECTION_REQUIRED && activeUserIds.isNotEmpty()) {
            return null
        }
        return record.copy(activeUserIds = activeUserIds)
    }

    private fun <T> withLockedRecord(operation: (LoadedRecord) -> LockedResult<T>): T? =
        synchronized(recordMutationLock) {
            runCatching {
                val configDir = Files.CONFIG_DIR
                require(configDir.isDirectory || configDir.mkdirs()) { "config_dir_unavailable" }
                val lockFile = File(configDir, LOCK_FILE_NAME)
                FileChannel.open(lockFile.toPath(), CREATE, WRITE).use { channel ->
                    // Wait for the module UI process to finish its short atomic update instead of
                    // treating transient lock contention as an account-slot revocation.
                    channel.lock().use {
                        val loaded = requireNotNull(readLockedRecord(configDir)) { "registry_read_failed" }
                        val result = operation(loaded)
                        val recordToWrite = result.updatedRecord ?: loaded.record.takeIf { loaded.needsWrite }
                        if (recordToWrite != null) {
                            check(writeLockedRecord(configDir, recordToWrite)) { "registry_write_failed" }
                        }
                        result.value
                    }
                }
            }.onFailure(::logRegistryUnavailable).getOrNull()
        }

    private fun logRegistryUnavailable(error: Throwable) {
        val now = System.currentTimeMillis()
        synchronized(recordMutationLock) {
            if (now - lastRegistryUnavailableLogAtMs < REGISTRY_UNAVAILABLE_LOG_INTERVAL_MS) {
                return
            }
            lastRegistryUnavailableLogAtMs = now
        }
        Log.record(
            TAG,
            "account_slot_registry_unavailable: ${error.javaClass.simpleName}:${error.message.orEmpty().take(200)}",
        )
    }

    private fun readLockedRecord(configDir: File): LoadedRecord? {
        val recordFile = File(configDir, RECORD_FILE_NAME)
        if (!recordFile.exists()) {
            return LoadedRecord(bootstrapRecord(), needsWrite = true)
        }
        val parsedRecord = runCatching {
            JsonUtil.parseObject(Files.readFromFile(recordFile), AccountSlotRecord::class.java)
        }.getOrNull() ?: return null
        val validatedRecord = validateRecord(parsedRecord) ?: return null
        val recoveredRecord = recoverRecord(validatedRecord)
        return LoadedRecord(recoveredRecord, needsWrite = recoveredRecord != validatedRecord)
    }

    private fun writeLockedRecord(configDir: File, record: AccountSlotRecord): Boolean {
        val canonicalRecord = validateRecord(record) ?: return false
        var temporaryFile: File? = null
        return runCatching {
            temporaryFile = File.createTempFile("$RECORD_FILE_NAME.", ".tmp", configDir)
            temporaryFile!!.writeText(JsonUtil.formatJson(canonicalRecord), Charsets.UTF_8)
            NioFiles.move(
                temporaryFile!!.toPath(),
                File(configDir, RECORD_FILE_NAME).toPath(),
                ATOMIC_MOVE,
                REPLACE_EXISTING,
            )
            true
        }.getOrDefault(false).also { success ->
            if (!success) {
                Log.record(TAG, "account_slot_registry_write_failed")
            }
            temporaryFile?.takeIf { it.exists() }?.delete()
        }
    }

    private fun shortHash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

package io.github.sds100.keymapper.logging

import android.util.Log
import io.github.sds100.keymapper.data.Keys
import io.github.sds100.keymapper.data.entities.LogEntryEntity
import io.github.sds100.keymapper.data.repositories.PreferenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.*

/**
 * Created by sds100 on 13/05/2021.
 */
class KeyMapperLoggingTree(
    private val coroutineScope: CoroutineScope,
    preferenceRepository: PreferenceRepository,
    private val logRepository: LogRepository
) : Timber.Tree() {
    private val logEverything: StateFlow<Boolean> = preferenceRepository.get(Keys.log)
        .map { it ?: false }
        .stateIn(coroutineScope, SharingStarted.Eagerly, false)

    private val messagesToLog = MutableSharedFlow<LogEntryEntity>(
        extraBufferCapacity = 1000,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    init {
        messagesToLog
            .onEach {
                logRepository.insertSuspend(it)
            }
            .flowOn(Dispatchers.Default)
            .launchIn(coroutineScope)
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        //error and info logs should always log even if the user setting is turned off
        if (!logEverything.value && priority != Log.ERROR && priority != Log.INFO) {
            return
        }

        val severity = when (priority) {
            Log.ERROR -> LogEntryEntity.SEVERITY_ERROR
            Log.DEBUG -> LogEntryEntity.SEVERITY_DEBUG
            Log.INFO -> LogEntryEntity.SEVERITY_INFO
            else -> LogEntryEntity.SEVERITY_DEBUG
        }

        messagesToLog.tryEmit(
            LogEntryEntity(
                id = 0,
                time = Calendar.getInstance().timeInMillis,
                severity = severity,
                message = message
            )
        )
    }
}
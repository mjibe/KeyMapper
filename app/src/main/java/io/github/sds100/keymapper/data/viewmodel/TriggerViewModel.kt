package io.github.sds100.keymapper.data.viewmodel

import android.view.KeyEvent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import io.github.sds100.keymapper.R
import io.github.sds100.keymapper.data.IPreferenceDataStore
import io.github.sds100.keymapper.data.model.DeviceInfo
import io.github.sds100.keymapper.data.model.NotifyUserModel
import io.github.sds100.keymapper.data.model.Trigger
import io.github.sds100.keymapper.data.model.TriggerKeyModel
import io.github.sds100.keymapper.data.model.options.TriggerKeyOptions
import io.github.sds100.keymapper.data.model.options.TriggerOptions
import io.github.sds100.keymapper.data.repository.DeviceInfoRepository
import io.github.sds100.keymapper.util.Data
import io.github.sds100.keymapper.util.Empty
import io.github.sds100.keymapper.util.KeyEventUtils
import io.github.sds100.keymapper.util.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import splitties.bitflags.withFlag
import java.util.*

/**
 * Created by sds100 on 24/11/20.
 */

class TriggerViewModel(
    private val mCoroutineScope: CoroutineScope,
    private val mDeviceInfoRepository: DeviceInfoRepository,
    preferenceDataStore: IPreferenceDataStore
) : IPreferenceDataStore by preferenceDataStore {

    val optionsViewModel = TriggerOptionsViewModel()

    private val _keys = MutableLiveData<List<Trigger.Key>>()
    val keys: LiveData<List<Trigger.Key>> = _keys

    val triggerInParallel: MutableLiveData<Boolean> = MutableLiveData(false)
    val triggerInSequence: MutableLiveData<Boolean> = MutableLiveData(false)
    val triggerModeUndefined: MutableLiveData<Boolean> = MutableLiveData(false)

    val mode: MediatorLiveData<Int> = MediatorLiveData<Int>().apply {
        addSource(triggerInParallel) {
            if (it == true) {

                /* when the user first chooses to make parallel a trigger, show a dialog informing them that
                the order in which they list the keys is the order in which they will need to be held down.
                 */
                if (_keys.value?.size!! > 1 &&
                    !getBoolPref(R.string.key_pref_shown_parallel_trigger_order_dialog)) {

                    val model = NotifyUserModel(R.string.dialog_message_parallel_trigger_order) {
                        setBoolPref(R.string.key_pref_shown_parallel_trigger_order_dialog, true)
                    }

                    notifyUser(model)
                }

                // set all the keys to a short press if coming from a non-parallel trigger
                // because they must all be the same click type and can't all be double pressed
                if (it == true && value != null && value != Trigger.PARALLEL) {
                    _keys.value?.let { keys ->
                        if (keys.isEmpty()) {
                            return@let
                        }

                        _keys.value = keys.map { key ->
                            key.copy(clickType = Trigger.SHORT_PRESS)
                        }
                    }
                }

                value = Trigger.PARALLEL
            }
        }

        addSource(triggerInSequence) {
            if (it == true) {
                value = Trigger.SEQUENCE

                if (_keys.value?.size!! > 1 &&
                    !getBoolPref(R.string.key_pref_shown_sequence_trigger_explanation_dialog)) {

                    val model = NotifyUserModel(R.string.dialog_message_sequence_trigger_explanation) {
                        setBoolPref(R.string.key_pref_shown_sequence_trigger_explanation_dialog, true)
                    }

                    notifyUser(model)
                }
            }
        }

        addSource(triggerModeUndefined) {
            if (it == true) {
                value = Trigger.UNDEFINED

                triggerInSequence.value = false
                triggerInParallel.value = false
            }
        }
    }

    val isParallelTriggerClickTypeShortPress = _keys.map {
        if (!it.isNullOrEmpty()) {
            it[0].clickType == Trigger.SHORT_PRESS
        } else {
            false
        }
    }

    val isParallelTriggerClickTypeLongPress = _keys.map {
        if (!it.isNullOrEmpty()) {
            it[0].clickType == Trigger.LONG_PRESS
        } else {
            false
        }
    }

    private val _modelList = MutableLiveData<State<List<TriggerKeyModel>>>()
    val modelList: LiveData<State<List<TriggerKeyModel>>> = _modelList

    val triggerKeyCount = modelList.map {
        when (it) {
            is Data -> it.data.size
            else -> 0
        }
    }

    private val _buildModelsEvent = MutableSharedFlow<List<Trigger.Key>>()
    val buildModelsEvent = _buildModelsEvent.asSharedFlow()

    private val _editTriggerKeyOptionsEvent = MutableSharedFlow<TriggerKeyOptions>()
    val editTriggerKeyOptionsEvent = _editTriggerKeyOptionsEvent.asSharedFlow()

    private val _notifyUserEvent = MutableSharedFlow<NotifyUserModel>()
    val notifyUserEvent = _notifyUserEvent.asSharedFlow()

    private val _promptToEnableCapsLockKeyboardLayout = MutableSharedFlow<Unit>()
    val promptToEnableCapsLockKeyboardLayout = _promptToEnableCapsLockKeyboardLayout.asSharedFlow()

    private val _promptToEnableAccessibilityService = MutableSharedFlow<Unit>()
    val promptToEnableAccessibilityService = _promptToEnableAccessibilityService.asSharedFlow()

    val recordTriggerTimeLeft = MutableLiveData(0)
    val recordingTrigger = MutableLiveData(false)

    private val _startRecordingTriggerInService = MutableSharedFlow<Unit>()
    val startRecordingTriggerInService = _startRecordingTriggerInService.asSharedFlow()

    private val _stopRecording = MutableSharedFlow<Unit>()
    val stopRecording = _stopRecording.asSharedFlow()

    fun setTrigger(trigger: Trigger) {
        _keys.value = trigger.keys

        optionsViewModel.setOptions(TriggerOptions(trigger.keys, trigger.mode, trigger.flags, trigger.extras))

        when (trigger.mode) {
            Trigger.PARALLEL -> {
                triggerInParallel.value = true
                triggerInSequence.value = false
                triggerModeUndefined.value = false
            }

            Trigger.SEQUENCE -> {
                triggerInSequence.value = true
                triggerInParallel.value = false
                triggerModeUndefined.value = false
            }

            Trigger.UNDEFINED -> {
                triggerInSequence.value = false
                triggerInParallel.value = false
                triggerModeUndefined.value = true
            }
        }
    }

    fun createTrigger(): Trigger? {
        return optionsViewModel.options.value?.apply(
            Trigger(
                keys = keys.value ?: listOf(),
                mode = mode.value ?: Trigger.DEFAULT_TRIGGER_MODE
            ))
    }

    fun setParallelTriggerClickType(@Trigger.ClickType clickType: Int) {
        _keys.value = keys.value?.map {
            it.copy(clickType = clickType)
        }

        invalidateOptions()
    }

    fun setTriggerKeyDevice(keyCode: Int, descriptor: String) {
        _keys.value = keys.value?.map {
            if (it.keyCode == keyCode) {
                return@map it.copy(deviceId = descriptor)
            }

            it
        }

        invalidateOptions()
    }

    /**
     * @return whether the key already exists has been added to the list
     */
    suspend fun addTriggerKey(keyCode: Int, deviceDescriptor: String, deviceName: String, isExternal: Boolean): Boolean {
        mDeviceInfoRepository.insertDeviceInfo(DeviceInfo(deviceDescriptor, deviceName))

        val containsKey = keys.value?.any {
            val sameKeyCode = keyCode == it.keyCode

            //if the key is not external, check whether a trigger key already exists for this device
            val sameDeviceId = if (
                (it.deviceId == Trigger.Key.DEVICE_ID_THIS_DEVICE
                    || it.deviceId == Trigger.Key.DEVICE_ID_ANY_DEVICE)
                && !isExternal) {
                true

            } else {
                it.deviceId == deviceDescriptor
            }

            sameKeyCode && sameDeviceId

        } ?: false

        if (containsKey) {
            return false
        }

        _keys.value = keys.value?.toMutableList()?.apply {
            val deviceId = if (isExternal) {
                deviceDescriptor
            } else {
                Trigger.Key.DEVICE_ID_THIS_DEVICE
            }

            var flags = 0

            if (KeyEventUtils.isModifierKey(keyCode)) {
                flags = flags.withFlag(Trigger.Key.FLAG_DO_NOT_CONSUME_KEY_EVENT)
            }

            val triggerKey = Trigger.Key(keyCode, deviceId, flags)

            add(triggerKey)
        }

        if (keys.value!!.size <= 1) {
            triggerModeUndefined.value = true
        }

        /* Automatically make it a parallel trigger when the user makes a trigger with more than one key
        because this is what most users are expecting when they make a trigger with multiple keys */
        if (keys.value!!.size == 2) {
            triggerInParallel.value = true
        }

        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            mCoroutineScope.launch {
                _promptToEnableCapsLockKeyboardLayout.emit(Unit)
            }
        }

        invalidateOptions()

        return true
    }

    fun setTriggerOption(id: String, newValue: Int) {
        optionsViewModel.setValue(id, newValue)
        invalidateOptions()
    }

    fun setTriggerOption(id: String, newValue: Boolean) {

        if (id == TriggerOptions.ID_SCREEN_OFF_TRIGGER &&
            !getBoolPref(R.string.key_pref_shown_screen_off_triggers_explanation)) {

            notifyUser(NotifyUserModel(R.string.showcase_screen_off_triggers) {
                setBoolPref(R.string.key_pref_shown_screen_off_triggers_explanation, true)
            })
        }

        optionsViewModel.setValue(id, newValue)
        invalidateOptions()
    }

    fun removeTriggerKey(keycode: Int) {
        _keys.value = keys.value?.toMutableList()?.apply {
            removeAll { it.keyCode == keycode }
        }

        if (keys.value!!.size <= 1) {
            triggerModeUndefined.value = true
        }

        invalidateOptions()
    }

    fun moveTriggerKey(fromIndex: Int, toIndex: Int) {
        _keys.value = keys.value?.toMutableList()?.apply {
            if (fromIndex < toIndex) {
                for (i in fromIndex until toIndex) {
                    Collections.swap(this, i, i + 1)
                }
            } else {
                for (i in fromIndex downTo toIndex + 1) {
                    Collections.swap(this, i, i - 1)
                }
            }
        }

        invalidateOptions()
    }

    fun editTriggerKeyOptions(id: String) = mCoroutineScope.launch {
        val key = keys.value?.find { it.uniqueId == id } ?: return@launch

        val options = TriggerKeyOptions(key, mode.value!!)

        _editTriggerKeyOptionsEvent.emit(options)
    }

    fun recordTrigger() = mCoroutineScope.launch {
        if (!recordingTrigger.value!!) {
            _startRecordingTriggerInService.emit(Unit)
        }
    }

    fun stopRecording() = mCoroutineScope.launch {
        if (recordingTrigger.value == true) {
            _stopRecording.emit(Unit)
        }
    }

    fun invalidateOptions() {
        val tempTrigger = createTrigger() ?: return

        optionsViewModel.setOptions(TriggerOptions(
            tempTrigger.keys,
            tempTrigger.mode,
            tempTrigger.flags,
            tempTrigger.extras
        ))
    }

    fun rebuildModels() = mCoroutineScope.launch {
        _buildModelsEvent.emit(keys.value ?: listOf())
    }

    fun setModelList(models: List<TriggerKeyModel>) {
        _modelList.value = when {
            models.isEmpty() -> Empty()
            else -> Data(models)
        }
    }

    private fun notifyUser(model: NotifyUserModel) = mCoroutineScope.launch {
        if (_notifyUserEvent.firstOrNull()?.message != model.message) {
            _notifyUserEvent.emit(model)
        }
    }

    suspend fun getDeviceInfoList() = mDeviceInfoRepository.getAll()
}
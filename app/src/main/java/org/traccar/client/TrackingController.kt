/*
 * Copyright 2015 - 2021 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.traccar.client.DatabaseHelper.DatabaseHandler
import org.traccar.client.NetworkManager.NetworkHandler
import org.traccar.client.PositionProvider.PositionListener
import org.traccar.client.ProtocolFormatter.formatRequest
import org.traccar.client.RequestManager.RequestHandler
import org.traccar.client.RequestManager.sendRequestAsync


class TrackingController(private val context: Context) : PositionListener, NetworkHandler {

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val positionProvider = PositionProviderFactory.create(context, this)
    private val databaseHelper = DatabaseHelper(context)
    private val networkManager = NetworkManager(context, this)

    private val url: String = preferences.getString(
        MainFragment.KEY_URL,
        context.getString(R.string.settings_url_default_value)
    )!!
    private val buffer: Boolean = preferences.getBoolean(MainFragment.KEY_BUFFER, true)
    private val canSendSMS: Boolean = preferences.getBoolean(MainFragment.KEY_SEND_SMS, false)

    private var isOnline = networkManager.isOnline
    private var isWaiting = false

    fun start() {
        if (isOnline) {
            read()
        }
        try {
            positionProvider.startUpdates()
        } catch (e: SecurityException) {
            Log.w(TAG, e)
        }
        networkManager.start()
    }

    fun stop() {
        networkManager.stop()
        try {
            positionProvider.stopUpdates()
        } catch (e: SecurityException) {
            Log.w(TAG, e)
        }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onPositionUpdate(position: Position) {
        StatusActivity.addMessage(context.getString(R.string.status_location_update))
        if (buffer) {
            write(position)
            if (canSendSMS)
                sendViaSMS(position)
        } else {
            send(position)
        }
    }

    private fun sendViaSMS(position: Position) {
        val phoneNum = preferences.getString(MainFragment.KEY_SMS_NUMBER, "09126930456")
        // The number on which you want to send SMS
        val smsManager: SmsManager
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                smsManager = context.getSystemService(SmsManager::class.java)
            } else {
                smsManager = SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNum, null, getSmsContent(position), null, null)
            Log.d(
                "TAG",
                "send via sms (id:${position.id} time:${position.time.time / 1000} lat:${position.latitude} lon:${position.longitude})"
            )

        } catch (e: Exception) {
            Log.d("TAG4", "sendViaSMS: Exception")
            Log.d("TAG4", "sendViaSMS: message:${e.message}")
            Toast.makeText(context.applicationContext, e.message.toString(), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun getSmsContent(position: Position): String {
        var smsContent: String =
            ("id:${position.deviceId}\n" +
                    "timestamp:${(position.time.time / 1000)}\n" +
                    "lat:${position.latitude}\n" +
                    "lon:${position.longitude}\n" +
//                    "speed:${position.speed}" +
//                    "bearing:${position.course}" +
//                    "altitude:${position.altitude}" +
                    "accuracy:${position.accuracy}\n" +
                    "batt:${position.battery}\n")

        if (position.charging) {
            smsContent += "charge:${position.charging}\n"
        }
        if (position.mock) {
            smsContent += "mock ${position.mock}"
        }
        return smsContent
    }

    override fun onPositionError(error: Throwable) {}
    override fun onNetworkUpdate(isOnline: Boolean) {
        val message =
            if (isOnline) R.string.status_network_online else R.string.status_network_offline
        StatusActivity.addMessage(context.getString(message))
        if (!this.isOnline && isOnline) {
            read()
        }
        this.isOnline = isOnline
    }

    //
    // State transition examples:
    //
    // write -> read -> send -> delete -> read
    //
    // read -> send -> retry -> read -> send
    //

    private fun log(action: String, position: Position?) {
        var formattedAction: String = action
        if (position != null) {
            formattedAction +=
                " (id:" + position.id +
                        " time:" + position.time.time / 1000 +
                        " lat:" + position.latitude +
                        " lon:" + position.longitude + ")"
        }
        Log.d(TAG, formattedAction)
    }

    private fun write(position: Position) {
        log("write", position)
        databaseHelper.insertPositionAsync(position, object : DatabaseHandler<Unit?> {
            override fun onComplete(success: Boolean, result: Unit?) {
                if (success) {
                    if (isOnline && isWaiting) {
                        read()
                        isWaiting = false
                    }
                }
            }
        })
    }

    private fun read() {
        log("read", null)
        databaseHelper.selectPositionAsync(object : DatabaseHandler<Position?> {
            override fun onComplete(success: Boolean, result: Position?) {
                if (success) {
                    if (result != null) {
                        if (result.deviceId == preferences.getString(
                                MainFragment.KEY_DEVICE,
                                null
                            )
                        ) {
                            send(result)
                        } else {
                            delete(result)
                        }
                    } else {
                        isWaiting = true
                    }
                } else {
                    retry()
                }
            }
        })
    }

    private fun delete(position: Position) {
        log("delete", position)
        databaseHelper.deletePositionAsync(position.id, object : DatabaseHandler<Unit?> {
            override fun onComplete(success: Boolean, result: Unit?) {
                if (success) {
                    read()
                } else {
                    retry()
                }
            }
        })
    }

    private fun send(position: Position) {
        log("send", position)
        val request = formatRequest(url, position)
        sendRequestAsync(request, object : RequestHandler {
            override fun onComplete(success: Boolean) {
                if (success) {
                    if (buffer) {
                        delete(position)
                    }
                } else {
                    StatusActivity.addMessage(context.getString(R.string.status_send_fail))
                    if (buffer) {
                        retry()
                    }
                }
            }
        })
    }

    private fun retry() {
        log("retry", null)
        handler.postDelayed({
            if (isOnline) {
                read()
            }
        }, RETRY_DELAY.toLong())
    }

    companion object {
        private val TAG = TrackingController::class.java.simpleName
        private const val RETRY_DELAY = 30 * 1000
    }

}

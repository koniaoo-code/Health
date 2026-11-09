/* Huami 2021 protocol adapted from Gadgetbridge, Copyright (C) 2021-2024
 Andreas Shimokawa, José Rebelo. AGPL-3.0-or-later. See LICENSE and NOTICE. */
package app.health

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Прямое (экспериментальное) подключение к Mi Band 7 по протоколу Huami 2021. */
@SuppressLint("MissingPermission")
class BandClient(private val context: Context, private val listener: Listener) : BluetoothGattCallback() {
    interface Listener {
        fun changed(status: String)
        fun found(name: String, address: String)
    }

    companion object {
        private val WRITE: UUID = UUID.fromString("00000016-0000-3512-2118-0009af100700")
        private val READ: UUID = UUID.fromString("00000017-0000-3512-2118-0009af100700")
        private val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val HR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    }

    private val h = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var write: BluetoothGattCharacteristic? = null
    private val queue = java.util.ArrayDeque<Runnable>()
    private var busy = false
    private var authenticated = false
    private var handle = 0
    private var expectedCount = 0
    private var inHandle = -1
    private var type = 0
    private var length = 0
    private var incoming: ByteArrayOutputStream? = null
    private var secret: ByteArray? = null
    private val privateKey = ByteArray(24)
    private var session: ByteArray? = null
    private var status = "Браслет не подключён"
    var steps = -1
        private set
    var battery = -1
        private set
    var heart = -1
        private set

    fun status(): String = status
    private fun state(text: String) {
        status = text
        h.post { listener.changed(text) }
    }

    fun scan() {
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                state("Включи Bluetooth в настройках телефона")
                return
            }
            scanner = adapter.bluetoothLeScanner
            state("Поиск Mi Band 7…")
            scanner?.startScan(scanCallback)
            h.postDelayed({
                stopScan()
                if (gatt == null) state("Поиск завершён. Выбери браслет из списка")
            }, 15000)
        } catch (error: SecurityException) {
            state("Разреши доступ к ближайшим устройствам")
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            try {
                var name = result.device.name
                if (name == null && result.scanRecord != null) name = result.scanRecord!!.deviceName
                if (name != null) {
                    val address = result.device.address
                    h.post { listener.found(name, address) }
                }
            } catch (ignored: SecurityException) {}
        }

        override fun onScanFailed(code: Int) {
            state("Ошибка поиска Bluetooth: $code")
        }
    }

    fun stopScan() {
        val activeScanner = scanner ?: return
        try {
            activeScanner.stopScan(scanCallback)
        } catch (ignored: SecurityException) {}
        scanner = null
    }

    fun connect(mac: String, key: String) {
        if (!permitted()) {
            state("Разреши доступ к Bluetooth")
            return
        }
        stopScan()
        disconnectGatt()
        try {
            val normalized = key.trim().replaceFirst("^0x".toRegex(), "")
            if (!Regex("[0-9a-fA-F]{32}").matches(normalized)) throw IllegalArgumentException()
            secret = ByteArray(16)
            for (index in 0 until 16) secret!![index] = Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16).toByte()
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: run {
                state("Bluetooth недоступен на этом телефоне")
                return
            }
            state("Подключение…")
            gatt = adapter.getRemoteDevice(mac).connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            h.postDelayed({
                if (!authenticated && gatt != null) {
                    state("Время ожидания истекло. Проверь ключ и закрой Zepp Life / Mi Fitness")
                    disconnectGatt()
                }
            }, 35000)
        } catch (error: Exception) {
            state("Проверь MAC-адрес и ключ: 32 шестнадцатеричных символа")
        }
    }

    private fun permitted(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun enqueue(task: Runnable) {
        if (!permitted()) {
            state("Разрешение Bluetooth отозвано")
            disconnectGatt()
            return
        }
        queue.add(task)
        next()
    }

    private fun next() {
        if (busy || queue.isEmpty()) return
        busy = true
        try {
            queue.remove().run()
        } catch (error: SecurityException) {
            state("Разрешение Bluetooth отозвано")
            disconnectGatt()
        }
    }

    private fun done() {
        h.removeCallbacks(timeout)
        busy = false
        next()
    }

    private val timeout: Runnable = Runnable {
        state("Bluetooth не ответил. Подключись заново")
        disconnectGatt()
    }

    private fun write(bytes: ByteArray) {
        enqueue {
            val active = gatt
            val target = write
            if (active == null || target == null) return@enqueue
            val result = active.writeCharacteristic(target, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (result != BluetoothStatusCodes.SUCCESS) {
                state("Ошибка записи Bluetooth: $result")
                disconnectGatt()
            } else h.postDelayed(timeout, 10000)
        }
    }

    private fun notifyOn(characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        val descriptor = characteristic.getDescriptor(CCC) ?: return
        enqueue {
            val active = gatt ?: return@enqueue
            active.setCharacteristicNotification(characteristic, true)
            val result = active.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (result != BluetoothStatusCodes.SUCCESS) {
                state("Не удалось включить уведомления браслета")
                disconnectGatt()
            } else h.postDelayed(timeout, 10000)
        }
    }

    override fun onConnectionStateChange(active: BluetoothGatt, status: Int, newState: Int) {
        h.post {
            if (active !== gatt) return@post
            if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                state("Браслет отключён" + if (status == 0) "" else " · код $status")
                disconnectGatt()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                state("Проверка сервисов…")
                try {
                    active.discoverServices()
                } catch (error: SecurityException) {
                    state("Нет разрешения Bluetooth")
                    disconnectGatt()
                }
            }
        }
    }

    override fun onServicesDiscovered(active: BluetoothGatt, result: Int) {
        h.post {
            if (active !== gatt) return@post
            if (result != 0) {
                state("Не удалось прочитать сервисы")
                disconnectGatt()
                return@post
            }
            var readDevice: BluetoothGattCharacteristic? = null
            var heartRate: BluetoothGattCharacteristic? = null
            for (service in active.services) for (characteristic in service.characteristics) {
                if (characteristic.uuid == WRITE) write = characteristic
                if (characteristic.uuid == READ) readDevice = characteristic
                if (characteristic.uuid == HR) heartRate = characteristic
            }
            val foundRead = readDevice
            if (write == null || foundRead == null) {
                state("Этот браслет не использует протокол Mi Band 7 (не Pro)")
                disconnectGatt()
                return@post
            }
            notifyOn(foundRead)
            notifyOn(heartRate)
            enqueue {
                busy = false
                SecureRandom().nextBytes(privateKey)
                val pub = ECDH_B163.ecdh_generate_public(privateKey)
                if (pub == null) {
                    state("Ошибка создания ключа")
                    disconnectGatt()
                    return@enqueue
                }
                val exchange = ByteArray(52)
                exchange[0] = 4
                exchange[1] = 2
                exchange[3] = 2
                System.arraycopy(pub, 0, exchange, 4, 48)
                send(0x82, exchange)
                state("Авторизация. Подтверди подключение на браслете")
            }
        }
    }

    private fun send(endpoint: Int, data: ByteArray) {
        val id = ++handle and 255
        var offset = 0
        var count = 0
        while (offset < data.size) {
            val header = if (count == 0) 11 else 5
            val n = Math.min(20 - header, data.size - offset)
            val packet = ByteArray(header + n)
            packet[0] = 3
            packet[1] = ((if (count == 0) 1 else 0) or (if (offset + n == data.size) 6 else 0)).toByte()
            packet[3] = id.toByte()
            packet[4] = count.toByte()
            if (count == 0) {
                ByteBuffer.wrap(packet, 5, 6).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).putShort(endpoint.toShort())
            }
            System.arraycopy(data, offset, packet, header, n)
            write(packet)
            offset += n
            count++
        }
    }

    override fun onCharacteristicWrite(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        h.post {
            if (active !== gatt) return@post
            if (status != 0) {
                state("Ошибка записи: $status")
                disconnectGatt()
            } else done()
        }
    }

    override fun onDescriptorWrite(active: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        h.post {
            if (active !== gatt) return@post
            if (status != 0) {
                state("Ошибка подписки: $status")
                disconnectGatt()
            } else done()
        }
    }

    override fun onCharacteristicChanged(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val received = value.clone()
        h.post {
            if (active !== gatt) return@post
            if (characteristic.uuid == HR && received.size >= 2) {
                heart = if ((received[0].toInt() and 1) == 0) received[1].toInt() and 255
                else if (received.size >= 3) (received[1].toInt() and 255) or ((received[2].toInt() and 255) shl 8)
                else -1
                state("Подключён · пульс $heart")
                return@post
            }
            if (characteristic.uuid != READ) return@post
            try {
                decode(received)
            } catch (error: Exception) {
                state("Неподдерживаемый ответ браслета. Переподключись")
                disconnectGatt()
            }
        }
    }

    @Throws(Exception::class)
    private fun decode(d: ByteArray) {
        if (d.size < 5 || d[0] != 3.toByte()) return
        val flags = d[1].toInt() and 255
        val id = d[3].toInt() and 255
        val count = d[4].toInt() and 255
        var pos = 5
        if (flags and 1 != 0) {
            if (d.size < 11) return
            val buffer = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            length = buffer.getInt(5)
            type = buffer.getShort(9).toInt() and 65535
            if (length < 1 || length > 65536) return
            incoming = ByteArrayOutputStream()
            inHandle = id
            expectedCount = 0
            pos = 11
        }
        val stream = incoming
        if (stream == null || inHandle != id || count != expectedCount++) return
        stream.write(d, pos, d.size - pos)
        if (flags and 4 != 0) write(byteArrayOf(3, 0x80.toByte(), 0, id.toByte(), count.toByte()))
        if (flags and 2 != 0) {
            val payload = stream.toByteArray()
            incoming = null
            var plain = payload
            if (flags and 8 != 0) {
                val activeSession = session ?: return
                val key = activeSession.clone()
                for (index in 0 until 16) key[index] = (key[index].toInt() xor (id and 255)).toByte()
                plain = aes(payload, key, Cipher.DECRYPT_MODE)
            }
            if (plain.size < length) return
            handlePayload(type, Arrays.copyOf(plain, length))
        }
    }

    private fun aes(data: ByteArray, key: ByteArray, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    @Throws(Exception::class)
    private fun handlePayload(endpoint: Int, p: ByteArray) {
        if (endpoint == 0x82 && p.size >= 3 && p[0] == 0x10.toByte()) {
            if (p[1] == 4.toByte() && p[2] == 1.toByte() && p.size >= 67) {
                val shared = ECDH_B163.ecdh_generate_shared(privateKey, Arrays.copyOfRange(p, 19, 67))
                    ?: throw IllegalArgumentException()
                session = ByteArray(16)
                for (index in 0 until 16) session!![index] = (shared[index + 8].toInt() xor secret!![index].toInt()).toByte()
                val rand = Arrays.copyOfRange(p, 3, 19)
                val reply = ByteArray(33)
                reply[0] = 5
                System.arraycopy(aes(rand, secret!!, Cipher.ENCRYPT_MODE), 0, reply, 1, 16)
                System.arraycopy(aes(rand, session!!, Cipher.ENCRYPT_MODE), 0, reply, 17, 16)
                send(0x82, reply)
            } else if (p[1] == 5.toByte() && p[2] == 1.toByte()) {
                authenticated = true
                state("Mi Band 7 подключён")
                send(0x16, byteArrayOf(3))
                send(0x16, byteArrayOf(5, 1))
                send(0x29, byteArrayOf(3))
                send(0x1d, byteArrayOf(4, 1))
                h.postDelayed(poll, 12000)
            } else {
                state("Авторизация отклонена. Проверь ключ браслета")
                disconnectGatt()
            }
        } else if (endpoint == 0x16) {
            val start = when (p[0].toInt()) {
                4 -> 2
                7 -> 1
                else -> -1
            }
            if (start > 0 && p.size >= start + 3) {
                steps = (p[start + 1].toInt() and 255) or ((p[start + 2].toInt() and 255) shl 8)
                Store.prefs(context).edit().putInt("band_steps_" + Store.day(), steps).apply()
                state("Mi Band 7 · данные обновлены")
            }
        } else if (endpoint == 0x29 && p.size >= 3 && p[0] == 4.toByte()) {
            battery = p[2].toInt() and 255
            state("Mi Band 7 · заряд $battery%")
        }
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (authenticated) {
                send(0x1d, byteArrayOf(4, 2))
                send(0x16, byteArrayOf(3))
                h.postDelayed(this, 12000)
            }
        }
    }

    private fun disconnectGatt() {
        h.removeCallbacks(timeout)
        h.removeCallbacks(poll)
        val active = gatt
        gatt = null
        if (active != null) {
            try {
                active.disconnect()
                active.close()
            } catch (ignored: SecurityException) {}
        }
        write = null
        busy = false
        queue.clear()
        authenticated = false
        privateKey.fill(0)
        secret?.fill(0)
        session = null
    }

    fun disconnect() {
        stopScan()
        disconnectGatt()
        state("Браслет не подключён")
    }
}

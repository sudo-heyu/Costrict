package com.heyu.safetybelt.operator.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * 蓝牙设备扫描结果的数据模型。
 *
 * @property device 扫描到的原生蓝牙设备对象。
 * @property rssi 设备的信号强度指示器 (RSSI)。数值越小，信号越弱。
 * @property bestName 设备的最佳可用名称。
 * @property lastSeen 设备最后一次被扫描到的时间戳。用于判断设备是否在线。在序列化时会被忽略。
 * @property deviceAddress 设备的MAC地址。
 */
@SuppressLint("MissingPermission")
@Parcelize
data class DeviceScanResult(
    val device: BluetoothDevice,
    var rssi: Int,
    val bestName: String
) : Parcelable {
    @IgnoredOnParcel
    var lastSeen: Long = 0
    val deviceAddress: String
        get() = device.address
}
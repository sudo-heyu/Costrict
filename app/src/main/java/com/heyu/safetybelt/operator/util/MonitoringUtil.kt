package com.heyu.safetybelt.operator.util

import android.graphics.Color

object MonitoringUtil {

    /**
     * 将 RSSI 值转换为信号强度字符串（“极强”、“强”、“中”、“弱”）。
     * 阈值基于常见的低功耗蓝牙信号水平，提供了更细致的区分。
     *
     * @param rssi 设备的 RSSI 值，通常为负整数。
     * @return 代表信号强度的字符串。
     */
    fun getSignalStrengthString(rssi: Int): String {
        return when {
            rssi >= -65 -> "极强"
            rssi >= -75 -> "强"
            rssi >= -85 -> "中"
            else -> "弱"
        }
    }

    /**
     * 根据信号强度字符串返回对应的颜色。
     * 为“极强”信号增加了更亮的绿色。
     *
     * @param signalStrength 信号强度字符串（“极强”、“强”、“中”、“弱”）。
     * @return 代表颜色的整数值。
     */
    fun getSignalStrengthColor(signalStrength: String): Int {
        return when (signalStrength) {
            "极强" -> Color.parseColor("#00C853") // A bright green for excellent signal
            "强" -> Color.GREEN
            "中" -> Color.YELLOW
            else -> Color.GRAY
        }
    }
}

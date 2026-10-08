package com.videobrowser.app

import android.media.MediaCodecList
import android.media.MediaFormat
import org.json.JSONObject

object CodecDetector {

    data class DeviceCapabilities(
        val supportsHevc: Boolean,
        val supportsAv1: Boolean,
        val supportsVp9: Boolean,
        val supports10Bit: Boolean
    ) {
        fun toJson(): String {
            return JSONObject().apply {
                put("hevc", supportsHevc)
                put("av1", supportsAv1)
                put("vp9", supportsVp9)
                put("10bit", supports10Bit)
            }.toString()
        }
    }

    fun getCapabilities(): DeviceCapabilities {
        var hasHevc = false
        var hasAv1 = false
        var hasVp9 = false
        var has10Bit = false

        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when (type.lowercase()) {
                        MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                            hasHevc = true
                            val caps = info.getCapabilitiesForType(type)
                            for (profile in caps.profileLevels) {
                                if (profile.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
                                    has10Bit = true
                                }
                            }
                        }
                        MediaFormat.MIMETYPE_VIDEO_AV1 -> hasAv1 = true
                        MediaFormat.MIMETYPE_VIDEO_VP9 -> hasVp9 = true
                    }
                }
            }
        } catch (_: Exception) {}

        return DeviceCapabilities(
            supportsHevc = hasHevc,
            supportsAv1 = hasAv1,
            supportsVp9 = hasVp9,
            supports10Bit = has10Bit
        )
    }
}

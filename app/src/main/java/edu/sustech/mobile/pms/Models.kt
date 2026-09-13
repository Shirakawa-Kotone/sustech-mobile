package edu.sustech.mobile.pms

import org.json.JSONArray
import org.json.JSONObject

// -- Wire constants (mirrors sustech_survival.pms.schema) ----------------------

object Paper {
    const val UNSPECIFIED = -1
    const val A3 = 8
    const val A4 = 9

    fun name(code: Int): String = when (code) {
        A4 -> "A4"
        A3 -> "A3"
        else -> ""
    }
}

object ColorMode {
    const val BW = 1
    const val COLOR = 2
}

object Duplex {
    const val SINGLE = 1
    const val SHORT_EDGE = 2
    const val LONG_EDGE = 3
}

object ReportType {
    const val PRINT = 1
    const val SCAN = 2
    const val COPY = 3
}

object DeviceProperty {
    const val PRINT = 1
    const val COPY = 2
    const val SCAN = 4
    const val COLOR = 8
}

/**
 * Derived printer state. Kept as an enum rather than a string so no UI copy
 * lives in the wire layer — the screen decides how to say it.
 */
enum class StationState { IDLE, BUSY, FAULT, NOT_OPEN }

// -- Records ------------------------------------------------------------------

data class ServerGroup(val sn: Int, val name: String) {
    override fun toString(): String = name

    companion object {
        fun from(raw: JSONObject) = ServerGroup(
            sn = raw.optInt("dwSN", 0),
            name = raw.optString("szName", ""),
        )
    }
}

data class Station(
    val devSn: Int,
    val name: String,
    val statusInfo: String,
    val status: Int,
    val trays: List<Int>,
    val property: Int,
) {
    val serverGroup: Int get() = devSn / 1000

    val isIdle: Boolean get() = status and 1 != 0
    val isBusy: Boolean get() = status and 2 != 0

    /** Mirrors the site's `backState` logic: 0 = closed, else idle/busy/fault bits. */
    val state: StationState
        get() = when {
            status == 0 -> StationState.NOT_OPEN
            isIdle -> StationState.IDLE
            isBusy -> StationState.BUSY
            else -> StationState.FAULT
        }

    val papers: List<String>
        get() {
            val out = ArrayList<String>()
            for (code in trays) {
                val name = Paper.name(code)
                if (name.isNotEmpty() && !out.contains(name)) out.add(name)
            }
            return out
        }

    val canPrint: Boolean get() = property and DeviceProperty.PRINT != 0
    val canCopy: Boolean get() = property and DeviceProperty.COPY != 0
    val canScan: Boolean get() = property and DeviceProperty.SCAN != 0
    val canColor: Boolean get() = property and DeviceProperty.COLOR != 0

    /** True when the device fault flags are set but no busy/idle bit explains it. */
    val hasFaultFlags: Boolean
        get() = status and (0x20 or 0x200 or 0x400 or 0x800 or 0x1000 or 0x2000 or 0x10000) != 0

    companion object {
        fun from(raw: JSONObject) = Station(
            devSn = raw.optInt("dwDevSN", 0),
            name = raw.optString("szName", ""),
            statusInfo = raw.optString("szStatInfo", ""),
            status = raw.optInt("dwStatus", 0),
            trays = listOf(
                raw.optInt("dwTrayPaper1", -1),
                raw.optInt("dwTrayPaper2", -1),
                raw.optInt("dwTrayPaper3", -1),
                raw.optInt("dwTrayPaper4", -1),
            ),
            property = raw.optInt("dwProperty", 0),
        )
    }
}

data class PrintJob(
    val jobId: Long,
    val fileName: String,
    val copies: Int,
    val attribute: String,
    val isColor: Boolean,
    /** One of [Duplex]'s codes. */
    val duplex: Int,
    val paper: String,
    val totalPages: Int,
    val dateText: String,
    val timeText: String,
) {
    val uploadedAt: String
        get() = listOf(dateText, timeText).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        fun from(raw: JSONObject): PrintJob {
            val attribute = raw.optString("szAttribe", "")
            val duplex = when {
                attribute.contains("vdup") -> Duplex.LONG_EDGE
                attribute.contains("hdup") -> Duplex.SHORT_EDGE
                else -> Duplex.SINGLE
            }

            var paper = ""
            var pages = 0
            val detailRaw = raw.optString("szPaperDetail", "")
            if (detailRaw.isNotEmpty()) {
                try {
                    val detail = JSONArray(detailRaw)
                    if (detail.length() > 0) {
                        val first = detail.getJSONObject(0)
                        paper = Paper.name(first.optInt("dwPaperID", -1))
                        pages = first.optInt("dwBWPages", 0) + first.optInt("dwColorPages", 0)
                    }
                } catch (_: Exception) {
                    // A malformed detail blob is not worth failing the whole list over.
                }
            }

            return PrintJob(
                jobId = raw.optLong("dwJobId", 0),
                fileName = raw.optString("szJobName", ""),
                copies = raw.optInt("dwCopies", 1),
                attribute = attribute,
                isColor = attribute.contains("color"),
                duplex = duplex,
                paper = paper,
                totalPages = pages,
                dateText = formatDate(raw.optInt("dwCreateDate", 0)),
                timeText = formatTime(raw.optInt("dwCreateTime", 0)),
            )
        }
    }
}

data class ScanJob(
    val jobId: Long,
    val fileName: String,
    val fileSize: Long,
    val dateText: String,
    val timeText: String,
) {
    val submittedAt: String
        get() = listOf(dateText, timeText).filter { it.isNotEmpty() }.joinToString(" ")

    val fileSizeText: String
        get() = when {
            fileSize >= 1024 * 1024 -> String.format("%.1f MB", fileSize / 1024.0 / 1024.0)
            fileSize > 0 -> String.format("%.0f KB", fileSize / 1024.0)
            else -> "—"
        }

    companion object {
        fun from(raw: JSONObject) = ScanJob(
            jobId = raw.optLong("dwJobId", 0),
            fileName = raw.optString("szDisplayName", ""),
            fileSize = raw.optLong("dwFileSize", 0),
            dateText = formatDate(raw.optInt("dwSubmitDate", 0)),
            timeText = formatTime(raw.optInt("dwSubmitTime", 0)),
        )
    }
}

data class UsageRecord(
    val sid: Long,
    val dateText: String,
    val timeText: String,
    val pages: Int,
    val paper: String,
    val unitFee: Int,
    val cardMoney: Int,
    val freeMoney: Int,
    val money: Int,
    val settleType: Int,
    val mfpSn: Long,
    /** One of [ReportType]'s codes. */
    val type: Int,
) {
    val happenedAt: String
        get() = listOf(dateText, timeText).filter { it.isNotEmpty() }.joinToString(" ")

    val moneyTotal: Double get() = (cardMoney + freeMoney + money) / 100.0

    val moneyText: String get() = String.format("¥%.2f", moneyTotal)

    /** Staff-billed vs self-service, as the site labels the column. */
    val billedByStaff: Boolean get() = settleType and 0xFF == 4

    companion object {
        fun from(raw: JSONObject): UsageRecord {
            val paperId = if (raw.has("dwPaperID")) raw.optInt("dwPaperID", -1)
            else raw.optInt("dwPaperId", -1)
            return UsageRecord(
                sid = raw.optLong("dwSID", 0),
                dateText = formatDate(raw.optInt("dwDate", 0)),
                timeText = formatTime(raw.optInt("dwTime", 0)),
                pages = raw.optInt("dwPages", 0),
                paper = Paper.name(paperId),
                unitFee = raw.optInt("dwUnitFee", 0),
                cardMoney = raw.optInt("dwUsedCardMoney", 0),
                freeMoney = raw.optInt("dwUsedFreeMoney", 0),
                money = raw.optInt("dwUsedMoney", 0),
                settleType = raw.optInt("dwSettleType", 0),
                mfpSn = raw.optLong("dwMFPSN", 0),
                type = raw.optInt("dwType", 0),
            )
        }
    }
}

/** Whatever `/Auth/Check` reports about the signed-in print account. */
data class AccountInfo(val trueName: String, val logonName: String, val raw: JSONObject)

// -- Helpers ------------------------------------------------------------------

internal fun formatDate(value: Int): String {
    if (value <= 0) return ""
    val s = value.toString().padStart(8, '0')
    if (s.length != 8) return ""
    return "${s.substring(0, 4)}.${s.substring(4, 6)}.${s.substring(6)}"
}

internal fun formatTime(value: Int): String {
    if (value <= 0) return ""
    val s = value.toString().padStart(6, '0')
    if (s.length < 6) return ""
    return "${s.substring(0, 2)}:${s.substring(2, 4)}:${s.substring(4, 6)}"
}

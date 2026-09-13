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

    fun label(code: Int): String = when (code) {
        SHORT_EDGE -> "双面短边"
        LONG_EDGE -> "双面长边"
        else -> "单面"
    }
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

    /** Mirrors printDev.js `backState`: 空闲 / 忙碌 / 不可用 / 未开放. */
    val stateText: String
        get() = when {
            status == 0 -> "未开放"
            isIdle -> "空闲"
            isBusy -> "忙碌"
            else -> {
                val info = statusInfo
                val short = if (info.contains("-")) info.substringBefore("-") else info
                short.ifEmpty { "不可用" }
            }
        }

    val papers: List<String>
        get() {
            val out = ArrayList<String>()
            for (code in trays) {
                val n = Paper.name(code)
                if (n.isNotEmpty() && !out.contains(n)) out.add(n)
            }
            return out
        }

    val canPrint: Boolean get() = property and DeviceProperty.PRINT != 0
    val canCopy: Boolean get() = property and DeviceProperty.COPY != 0
    val canScan: Boolean get() = property and DeviceProperty.SCAN != 0
    val canColor: Boolean get() = property and DeviceProperty.COLOR != 0

    val functionsText: String
        get() {
            val parts = ArrayList<String>()
            if (canPrint) parts.add("打印")
            if (canCopy) parts.add("复印")
            if (canScan) parts.add("扫描")
            if (canColor) parts.add("支持彩色")
            return parts.joinToString("，")
        }

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
    val duplexLabel: String,
    val paper: String,
    val totalPages: Int,
    val dateText: String,
    val timeText: String,
) {
    val uploadedAt: String
        get() = listOf(dateText, timeText).filter { it.isNotEmpty() }.joinToString(" ")

    val optionsText: String
        get() {
            val parts = ArrayList<String>()
            if (paper.isNotEmpty()) parts.add(paper)
            if (totalPages > 0) parts.add("$totalPages 页")
            parts.add("$copies 份")
            parts.add(if (isColor) "彩色" else "黑白")
            parts.add(duplexLabel)
            return parts.joinToString(" · ")
        }

    companion object {
        fun from(raw: JSONObject): PrintJob {
            val attribute = raw.optString("szAttribe", "")
            val duplexLabel = when {
                attribute.contains("vdup") -> "双面长边"
                attribute.contains("hdup") -> "双面短边"
                else -> "单面"
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
                duplexLabel = duplexLabel,
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
    val type: Int,
    val memo: String,
) {
    val happenedAt: String
        get() = listOf(dateText, timeText).filter { it.isNotEmpty() }.joinToString(" ")

    val moneyTotal: Double get() = (cardMoney + freeMoney + money) / 100.0

    val moneyText: String get() = String.format("¥%.2f", moneyTotal)

    val settleLabel: String get() = if (settleType and 0xFF == 4) "手工收费" else "自助收费"

    val typeLabel: String
        get() = when (type) {
            ReportType.SCAN -> "扫描"
            ReportType.COPY -> "复印"
            else -> "打印"
        }

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
                memo = raw.optString("szMemo", ""),
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

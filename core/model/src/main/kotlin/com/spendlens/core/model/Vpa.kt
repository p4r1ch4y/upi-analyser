package com.spendlens.core.model

/** UPI Virtual Payment Address */
data class Vpa(val value: String) {
    init {
        require(value.contains("@")) { "VPA must contain @" }
    }

    val handle: String get() = value.substringBefore("@")
    val psp: String get() = value.substringAfter("@")

    /** Clean for display: remove PSP suffix, title case */
    fun displayName(): String {
        return handle
            .replace(Regex("[._-]"), " ")
            .split(" ")
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    /** Detect VPA structure pattern */
    fun detectPattern(): VpaPattern {
        return when {
            handle.matches(Regex("^\\d{10}$")) -> VpaPattern.PERSON_PHONE
            handle.startsWith("paytmqr") && psp == "paytm" -> VpaPattern.PAYTM_MERCHANT_QR
            handle.startsWith("bharatpe.") -> VpaPattern.BHARATPE_MERCHANT
            handle.matches(Regex("^q\\d+$")) && psp == "ybl" -> VpaPattern.PHONEPE_MERCHANT_QR
            handle.contains(".rzp") -> VpaPattern.RAZORPAY_MERCHANT
            handle.matches(Regex("^[a-z]+stores?$")) -> VpaPattern.NAMED_MERCHANT
            psp in listOf("ybl", "ibl", "axl") -> VpaPattern.PHONEPE_PSP
            psp.startsWith("ok") -> VpaPattern.GPAY_PSP
            else -> VpaPattern.UNKNOWN
        }
    }

    enum class VpaPattern {
        PERSON_PHONE,
        PAYTM_MERCHANT_QR,
        BHARATPE_MERCHANT,
        PHONEPE_MERCHANT_QR,
        RAZORPAY_MERCHANT,
        NAMED_MERCHANT,
        PHONEPE_PSP,
        GPAY_PSP,
        UNKNOWN
    }

    companion object {
        fun parse(value: String): Vpa? {
            val cleaned = value.trim().lowercase()
            return if (cleaned.contains("@")) Vpa(cleaned) else null
        }
    }
}

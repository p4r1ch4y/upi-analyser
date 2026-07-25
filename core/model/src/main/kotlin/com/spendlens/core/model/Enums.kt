package com.spendlens.core.model

enum class Direction {
    DEBIT,   // Money out
    CREDIT;  // Money in

    companion object {
        fun parse(value: String): Direction? {
            return when (value.lowercase()) {
                "debit", "debited", "paid", "sent" -> DEBIT
                "credit", "credited", "received" -> CREDIT
                else -> null
            }
        }
    }
}

enum class Channel {
    UPI,
    CARD,
    NEFT,
    IMPS,
    RTGS,
    ATM,
    CASH,
    UNKNOWN;

    companion object {
        fun parse(value: String): Channel {
            return when (value.uppercase()) {
                "UPI" -> UPI
                "CARD", "DEBIT CARD", "CREDIT CARD" -> CARD
                "NEFT" -> NEFT
                "IMPS" -> IMPS
                "RTGS" -> RTGS
                "ATM" -> ATM
                "CASH" -> CASH
                else -> UNKNOWN
            }
        }
    }
}

enum class Instrument {
    SAVINGS,
    CREDIT_CARD,
    WALLET,
    PREPAID,
    UNKNOWN;

    companion object {
        fun parse(value: String): Instrument {
            return when (value.uppercase()) {
                "SAVINGS", "SAVING" -> SAVINGS
                "CREDIT CARD", "CC" -> CREDIT_CARD
                "WALLET" -> WALLET
                "PREPAID" -> PREPAID
                else -> UNKNOWN
            }
        }
    }
}

enum class Source {
    NOTIFICATION,
    SMS,
    STATEMENT,
    MANUAL;

    fun toMask(): Int = when (this) {
        NOTIFICATION -> 1
        SMS -> 2
        STATEMENT -> 4
        MANUAL -> 8
    }
}

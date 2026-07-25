package com.spendlens.core.model

/** Sortable, offline-generatable unique ID based on ULID spec */
@JvmInline
value class TxnId(val value: String) {
    companion object {
        private val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()
        
        fun generate(timestamp: Long = System.currentTimeMillis()): TxnId {
            val random = java.security.SecureRandom()
            val buffer = CharArray(26)
            
            // Encode timestamp (10 chars)
            var time = timestamp
            for (i in 9 downTo 0) {
                buffer[i] = ENCODING[(time % 32).toInt()]
                time /= 32
            }
            
            // Encode randomness (16 chars)
            val randomBytes = ByteArray(10)
            random.nextBytes(randomBytes)
            var value = 0L
            for (i in 0 until 10) {
                value = (value shl 8) or (randomBytes[i].toLong() and 0xFF)
                if ((i + 1) % 2 == 0) {
                    for (j in 0 until 3) {
                        buffer[10 + ((i / 2) * 3) + j] = ENCODING[(value % 32).toInt()]
                        value /= 32
                    }
                    value = 0
                }
            }
            
            return TxnId(String(buffer))
        }
        
        fun fromString(value: String): TxnId {
            require(value.length == 26) { "ULID must be 26 characters" }
            return TxnId(value)
        }
    }
}

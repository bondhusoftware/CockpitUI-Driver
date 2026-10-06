package com.bondhu.cockpitdriver

data class RechargeRequest(val phone: String, val amount: String)

object DriverSession {
    @Volatile var running: Boolean = false
    @Volatile var phone: String = ""
    @Volatile var amount: String = ""
    @Volatile var state: DriverState = DriverState.IDLE
    @Volatile var nextAttempts: Int = 0
    @Volatile var confirmAttempts: Int = 0
    @Volatile var pinAttempts: Int = 0
    @Volatile var okAttempts: Int = 0
    @Volatile var lastMessage: String = "প্রস্তুত"

    // v15: bulk queue — একের পর এক রিচার্জ
    @Volatile var queue: List<RechargeRequest> = emptyList()
    @Volatile var currentIndex: Int = 0
    @Volatile var completedCount: Int = 0
    @Volatile var failedCount: Int = 0
    @Volatile var successCounted: Boolean = false

    val totalCount: Int get() = queue.size
    fun hasNext(): Boolean = currentIndex + 1 < queue.size

    fun start(phoneNumber: String, rechargeAmount: String) =
        startBulk(listOf(RechargeRequest(phoneNumber, rechargeAmount)))

    fun startBulk(requests: List<RechargeRequest>) {
        queue = requests.toList()
        currentIndex = 0
        completedCount = 0
        failedCount = 0
        successCounted = false
        okAttempts = 0
        nextAttempts = 0
        confirmAttempts = 0
        pinAttempts = 0
        loadCurrent()
        running = true
        state = DriverState.OPENING
        lastMessage = if (requests.size > 1) {
            "বাল্ক রিচার্জ শুরু — ${requests.size}টি রিকোয়েস্ট"
        } else {
            "Cockpit খোলা হচ্ছে…"
        }
    }

    private fun loadCurrent() {
        val r = queue.getOrNull(currentIndex)
        phone = r?.phone.orEmpty()
        amount = r?.amount.orEmpty()
    }

    /** সফল রিচার্জের OK-এর পর পরের রিকোয়েস্টে যাও। */
    fun moveToNext() {
        if (hasNext()) {
            currentIndex++
            loadCurrent()
            successCounted = false
            nextAttempts = 0
            confirmAttempts = 0
            pinAttempts = 0
        }
    }

    fun stop(message: String = "থামানো হয়েছে") {
        running = false
        state = DriverState.STOPPED
        lastMessage = message
    }
}

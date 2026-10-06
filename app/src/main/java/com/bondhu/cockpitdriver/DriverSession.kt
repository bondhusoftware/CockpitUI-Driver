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
    @Volatile var fillAttempts: Int = 0
    @Volatile var lastMessage: String = "প্রস্তুত"
    @Volatile var lastNodeDump: String = ""

    // v18: bulk queue — Cockpit-এর "+" সিস্টেমে এক ট্রানজাকশনেই সব রিকোয়েস্ট
    @Volatile var queue: List<RechargeRequest> = emptyList()
    @Volatile var completedCount: Int = 0
    @Volatile var failedCount: Int = 0
    @Volatile var successCounted: Boolean = false

    // v19: নিচে লগে নম্বরের পাশে স্ট্যাটাস — ক্লিকে বিস্তারিত
    @Volatile var statuses: List<String> = emptyList()
    @Volatile var statusDetails: List<String> = emptyList()

    val totalCount: Int get() = queue.size

    fun setAllStatus(s: String, detail: String = "") {
        statuses = queue.map { s }
        if (detail.isNotEmpty()) statusDetails = queue.map { detail }
    }

    fun nowTime(): String = try {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
    } catch (_: Exception) { "" }

    fun start(phoneNumber: String, rechargeAmount: String) =
        startBulk(listOf(RechargeRequest(phoneNumber, rechargeAmount)))

    fun startBulk(requests: List<RechargeRequest>) {
        queue = requests.toList()
        val first = requests.firstOrNull()
        phone = first?.phone.orEmpty()
        amount = first?.amount.orEmpty()
        completedCount = 0
        failedCount = 0
        successCounted = false
        okAttempts = 0
        fillAttempts = 0
        nextAttempts = 0
        confirmAttempts = 0
        pinAttempts = 0
        statuses = requests.map { "⏳ অপেক্ষায়" }
        statusDetails = requests.map { "" }
        running = true
        state = DriverState.OPENING
        lastMessage = if (requests.size > 1) {
            "বাল্ক রিচার্জ শুরু — ${requests.size}টি নম্বর"
        } else {
            "Cockpit খোলা হচ্ছে…"
        }
    }

    fun stop(message: String = "থামানো হয়েছে") {
        running = false
        state = DriverState.STOPPED
        lastMessage = message
    }
}

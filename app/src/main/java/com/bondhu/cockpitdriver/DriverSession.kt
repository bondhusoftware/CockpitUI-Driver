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
    // v22: fill কোথায় আটকাচ্ছে — স্ট্যাটাসে দেখানোর জন্য
    @Volatile var fillDebug: String = ""

    // v26: পাওয়ারলোড — আলাদা বক্সে সেভ থাকা পরিমাণ (যেমন: 19, 29, 98)
    @Volatile var plAmounts: Set<String> = emptySet()
    // v26: ব্যাচ — PL রিকোয়েস্ট আলাদা single-এ, normal একসাথে বাল্কে
    // প্রতিটা ব্যাচ: (রিকোয়েস্ট লিস্ট, মাস্টার ইন্ডেক্স লিস্ট, PL কিনা)
    @Volatile var batches: List<Triple<List<RechargeRequest>, List<Int>, Boolean>> = emptyList()
    @Volatile var batchIndex: Int = 0
    @Volatile var batchMasterIndices: List<Int> = emptyList()
    // v26: মাস্টার লিস্ট — UI-তে সব রিকোয়েস্ট দেখানোর জন্য
    @Volatile var masterRequests: List<RechargeRequest> = emptyList()
    @Volatile var masterStatuses: MutableList<String> = mutableListOf()
    @Volatile var masterDetails: MutableList<String> = mutableListOf()
    // v26: বর্তমান ব্যাচ PL কিনা + PL স্টেজ (0=fill, 1=পাওয়ারলোড চাপা, 2=অফার খোঁজা)
    @Volatile var isPlBatch: Boolean = false
    @Volatile var plStage: Int = 0
    @Volatile var plOfferNotFound: Boolean = false

    val totalCount: Int get() = queue.size

    /** v26: পরিমাণ PL লিস্টে আছে কিনা ("98.0" → "98" normalize করে) */
    fun isPlAmount(amount: String): Boolean {
        if (plAmounts.isEmpty()) return false
        val norm = try {
            val d = amount.trim().toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else amount.trim()
        } catch (_: Exception) {
            amount.trim()
        }
        return plAmounts.contains(norm)
    }

    fun setAllStatus(s: String, detail: String = "") {
        statuses = queue.map { s }
        if (detail.isNotEmpty()) statusDetails = queue.map { detail }
        // v26: মাস্টার লিস্টেও আপডেট করো
        for ((localIdx, masterIdx) in batchMasterIndices.withIndex()) {
            if (masterIdx < masterStatuses.size) {
                masterStatuses[masterIdx] = s
                if (detail.isNotEmpty() && masterIdx < masterDetails.size) {
                    masterDetails[masterIdx] = detail
                }
            }
        }
    }

    /** v26: একটা রিকোয়েস্টের স্ট্যাটাস (লোকাল ইন্ডেক্স → মাস্টার) */
    fun setStatus(localIdx: Int, s: String, detail: String = "") {
        if (localIdx < statuses.size) {
            statuses = statuses.toMutableList().also { it[localIdx] = s }
            if (detail.isNotEmpty()) {
                statusDetails = statusDetails.toMutableList().also { it[localIdx] = detail }
            }
        }
        val masterIdx = batchMasterIndices.getOrNull(localIdx) ?: return
        if (masterIdx < masterStatuses.size) {
            masterStatuses[masterIdx] = s
            if (detail.isNotEmpty() && masterIdx < masterDetails.size) {
                masterDetails[masterIdx] = detail
            }
        }
    }

    fun nowTime(): String = try {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
    } catch (_: Exception) { "" }

    fun start(phoneNumber: String, rechargeAmount: String) =
        startBulk(listOf(RechargeRequest(phoneNumber, rechargeAmount)), emptySet())

    fun startBulk(requests: List<RechargeRequest>, plAmounts: Set<String> = emptySet()) {
        this.plAmounts = plAmounts
        // v26: মাস্টার লিস্ট (ইনপুট অর্ডারে)
        masterRequests = requests.toList()
        masterStatuses = requests.map { "⏳ অপেক্ষায়" }.toMutableList()
        masterDetails = requests.map { "" }.toMutableList()
        // v26: PL আলাদা single-এ, normal একসাথে বাল্কে
        val newBatches = ArrayList<Triple<List<RechargeRequest>, List<Int>, Boolean>>()
        requests.forEachIndexed { idx, r ->
            if (isPlAmount(r.amount)) {
                newBatches.add(Triple(listOf(r), listOf(idx), true))
            }
        }
        val normalWithIdx = requests.mapIndexedNotNull { idx, r ->
            if (!isPlAmount(r.amount)) idx to r else null
        }
        if (normalWithIdx.isNotEmpty()) {
            // v23: Cockpit-এ সর্বোচ্চ ৫ রো
            val take = normalWithIdx.take(5)
            newBatches.add(Triple(
                take.map { it.second },
                take.map { it.first },
                false
            ))
        }
        batches = newBatches
        batchIndex = 0
        startBatch(0)
    }

    /** v26: ব্যাচ শুরু — queue, স্ট্যাটাস, কাউন্টার রিসেট */
    fun startBatch(index: Int) {
        if (index >= batches.size) return
        batchIndex = index
        val (reqs, masterIdxs, isPl) = batches[index]
        // v23: Cockpit-এ সর্বোচ্চ ৫ রো — এর বেশি এলে প্রথম ৫টা নেওয়া হয়
        queue = reqs.take(5).toList()
        batchMasterIndices = masterIdxs.take(5)
        isPlBatch = isPl
        plStage = 0
        plOfferNotFound = false
        val first = reqs.firstOrNull()
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
        statuses = reqs.map { "⏳ অপেক্ষায়" }
        statusDetails = reqs.map { "" }
        fillDebug = ""
        running = true
        state = DriverState.OPENING
        val plTag = if (isPlBatch) " (⚡ পাওয়ারলোড)" else ""
        val batchInfo = if (batches.size > 1) " [${index + 1}/${batches.size}]" else ""
        lastMessage = if (reqs.size > 1) {
            "বাল্ক রিচার্জ শুরু — ${reqs.size}টি নম্বর$plTag$batchInfo"
        } else {
            "রিচার্জ শুরু$plTag$batchInfo — Cockpit খোলা হচ্ছে…"
        }
    }

    /** v26: বর্তমান ব্যাচ শেষ — পরের ব্যাচ থাকলে শুরু করো, নাহলে true (সব শেষ) */
    fun nextBatch(): Boolean {
        if (batchIndex + 1 < batches.size) {
            startBatch(batchIndex + 1)
            return false
        }
        return true
    }

    fun hasMoreBatches(): Boolean = batchIndex + 1 < batches.size

    fun stop(message: String = "থামানো হয়েছে") {
        running = false
        state = DriverState.STOPPED
        lastMessage = message
    }
}

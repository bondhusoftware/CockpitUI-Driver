package com.bondhu.cockpitdriver

object DriverSession {
    @Volatile var running: Boolean = false
    @Volatile var phone: String = ""
    @Volatile var amount: String = ""
    @Volatile var state: DriverState = DriverState.IDLE
    @Volatile var nextAttempts: Int = 0
    @Volatile var lastMessage: String = "প্রস্তুত"
    @Volatile var lastNodeDump: String = ""

    fun start(phoneNumber: String, rechargeAmount: String) {
        phone = phoneNumber
        amount = rechargeAmount
        running = true
        nextAttempts = 0
        state = DriverState.OPENING
        lastMessage = "Cockpit খোলা হচ্ছে…"
    }

    fun stop(message: String = "থামানো হয়েছে") {
        running = false
        state = DriverState.STOPPED
        lastMessage = message
    }
}

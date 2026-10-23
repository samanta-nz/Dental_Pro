package com.example.data

/** All monetary values are integer tomans. Cheques move cash only on confirmed clearing. */
val Payment.cashBankDelta: Long
    get() = when {
        isDeleted || bankAccountId == null -> 0L
        type != "CHEQUE" -> amount
        chequeStatus == "CLEARED" -> amount
        else -> 0L
    }

val ExpensePayment.cashBankDelta: Long
    get() = when {
        isDeleted || bankAccountId == null -> 0L
        method == "CHEQUE_TRANSFER" -> 0L
        method == "CHEQUE" && chequeStatus != "CLEARED" -> 0L
        else -> Math.negateExact(amount)
    }

val PersonalTransaction.cashBankDelta: Long
    get() = when {
        isDeleted || bankAccountId == null || paymentMethod == "CHEQUE_TRANSFER" -> 0L
        type == "INCOME" -> amount
        // DRAW is money withdrawn FROM the clinic, so the linked clinic account decreases.
        else -> Math.negateExact(amount)
    }

val ExpensePayment.postedDelta: Long
    get() = if (countsAsPartyPayment()) Math.negateExact(amount) else 0L

val PersonalTransaction.postedDelta: Long
    get() = if (isDeleted) 0L else if (type == "INCOME") amount else Math.negateExact(amount)

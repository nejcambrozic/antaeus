package io.pleo.antaeus.core.services.billing

enum class PaymentResult {
    PAID,
    FAILED,
    CUSTOMER_NOT_FOUND,
    NETWORK_ERROR,
    CURRENCY_MISSMATCH
}


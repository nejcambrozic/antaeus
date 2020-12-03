package io.pleo.antaeus.core.services.billing

import io.pleo.antaeus.models.Invoice

data class InvoicePaymentAction(val invoice: Invoice, val charged: Boolean)

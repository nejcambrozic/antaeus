package io.pleo.antaeus.core.exceptions

class InvoiceNotPendingException(invoiceId: Int) :
    Exception("Invoice '$invoiceId' not in pending state")
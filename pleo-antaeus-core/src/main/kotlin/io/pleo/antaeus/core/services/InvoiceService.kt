/*
    Implements endpoints related to invoices.
 */

package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.InvoiceNotFoundException
import io.pleo.antaeus.data.AntaeusDal
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus

class InvoiceService(private val dal: AntaeusDal) {
    fun fetchAll(): List<Invoice> {
        return dal.fetchInvoices()
    }

    fun fetch(id: Int): Invoice {
        return dal.fetchInvoice(id) ?: throw InvoiceNotFoundException(id)
    }

    fun fetchAllWithStatus(status: InvoiceStatus): List<Invoice> {
        return dal.fetchInvoices(status)
    }

    fun markProcessing(id: Int): Invoice {
        return dal.setInvoiceStatus(id, InvoiceStatus.PROCESSING) ?: throw InvoiceNotFoundException(id)
    }

    fun markPaid(id: Int): Invoice {
        return dal.setInvoiceStatus(id, InvoiceStatus.PAID) ?: throw InvoiceNotFoundException(id)
    }

    fun markFailed(id: Int): Invoice {
        return dal.setInvoiceStatus(id, InvoiceStatus.FAILED) ?: throw InvoiceNotFoundException(id)
    }
}

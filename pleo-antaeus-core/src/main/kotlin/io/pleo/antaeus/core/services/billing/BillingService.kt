package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.InvoiceNotPendingException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.core.services.billing.InvoicePayment
import io.pleo.antaeus.core.services.billing.PaymentResult
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}


class BillingService(
    private val paymentProvider: PaymentProvider,
    private val invoiceService: InvoiceService
) {
    fun processInvoices() {
        logger.info { "Starting to process invoices..." }
        val start = System.currentTimeMillis()

        val pendingInvoices = invoiceService.fetchAllWithStatus(InvoiceStatus.PENDING)
        val (paid, failed) = pendingInvoices.map { processInvoice(it) }.partition { it.charged }
        val end = System.currentTimeMillis()

        logger.info { "Finished processing ${pendingInvoices.size} invoices in ${end - start} ms" }

        logger.info { "Successfully processed ${paid.size} invoices" }
        logger.info { "Failed processing ${failed.size} invoices" }
    }


    private fun processInvoice(invoice: Invoice): InvoicePayment {
        logger.info { "Processing invoice '${invoice.id}'" }
        invoiceService.markProcessing(invoice.id)

        return try {
            val paid = paymentProvider.charge(invoice)
            if (paid) {
                logger.info { "Charge successful for invoice: '${invoice.id}'" }
                invoiceService.markPaid(invoice.id)
                InvoicePayment(invoice, paid, PaymentResult.PAID)
            }
            logger.info { "Charge failed for invoice: '${invoice.id}'" }
            invoiceService.markFailed(invoice.id)
            InvoicePayment(invoice, paid, PaymentResult.FAILED)

        } catch (cnfe: CustomerNotFoundException) {
            TODO()
        } catch (cme: CurrencyMismatchException) {
            TODO()
        } catch (ne: NetworkException) {
            TODO()
        }
    }
}

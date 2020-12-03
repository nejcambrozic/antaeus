package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.InvoiceNotPendingException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.core.scheduler.Scheduler
import io.pleo.antaeus.core.services.billing.InvoicePaymentAction
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import mu.KotlinLogging
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}


class BillingService(
    private val paymentProvider: PaymentProvider,
    private val invoiceService: InvoiceService,
    private val scheduler: Scheduler
) {

    fun startAutomaticBilling() {
        val now = LocalDateTime.now()
        // Schedule for next minute for testing purposes
        // TODO: schedule for 1st of month
        val scheduledAt = now.plusMinutes(1)
        scheduler.scheduleJob(processInvoices, scheduledAt)
    }

    private val processInvoices: () -> Unit = {
        logger.info { "Starting to process invoices..." }
        val start = System.currentTimeMillis()

        val pendingInvoices = invoiceService.fetchAllWithStatus(InvoiceStatus.PENDING)
        val (paid, failed) = pendingInvoices.map { processInvoice(it) }.partition { it.charged }
        val end = System.currentTimeMillis()

        logger.info { "Finished processing ${pendingInvoices.size} invoices in ${end - start} ms" }

        logger.info { "Successfully processed ${paid.size} invoices" }
        logger.info { "Failed processing ${failed.size} invoices" }
    }

    fun processInvoice(invoiceId: Int): InvoicePaymentAction {
        val invoice = invoiceService.fetch(invoiceId)
        return processInvoice(invoice)
    }

    fun processInvoice(invoice: Invoice): InvoicePaymentAction {
        return try {
            val processedInvoice = chargeInvoice(verifyInvoiceStatus(invoice))
            InvoicePaymentAction(processedInvoice, processedInvoice.status == InvoiceStatus.PAID)

        } catch (cnfe: CustomerNotFoundException) {
            TODO()
        } catch (cme: CurrencyMismatchException) {
            TODO()
        } catch (ne: NetworkException) {
            TODO()
        }
    }

    private fun verifyInvoiceStatus(invoice: Invoice): Invoice {
        if (invoice.status != InvoiceStatus.PENDING) {
            throw InvoiceNotPendingException(invoice.id)
        }
        logger.info { "Processing invoice '${invoice.id}'" }
        return invoiceService.markProcessing(invoice.id)
    }

    private fun chargeInvoice(invoice: Invoice): Invoice {
        val paid = paymentProvider.charge(invoice)
        if (paid) {
            logger.info { "Charge successful for invoice: '${invoice.id}'" }
            return invoiceService.markPaid(invoice.id)
        }
        logger.info { "Charge failed for invoice: '${invoice.id}'" }
        return invoiceService.markFailed(invoice.id)
    }
}

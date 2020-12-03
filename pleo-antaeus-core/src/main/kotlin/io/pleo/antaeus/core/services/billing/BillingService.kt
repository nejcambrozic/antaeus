package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.InvoiceNotPendingException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.core.scheduler.Scheduler
import io.pleo.antaeus.core.services.billing.InvoicePayment
import io.pleo.antaeus.core.services.billing.PaymentResult
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
        val scheduledAt = LocalDateTime.of(now.year, now.month, now.dayOfMonth, now.hour, now.minute + 1)
        scheduler.scheduleJob(processInvoices, scheduledAt)
    }

    private val processInvoices:() -> Unit = {
        logger.info { "Starting to process invoices..." }
        val start = System.currentTimeMillis()

        val pendingInvoices = invoiceService.fetchAllWithStatus(InvoiceStatus.PENDING)
        val (paid, failed) = pendingInvoices.map { processInvoice(it) }.partition { it.charged }
        val end = System.currentTimeMillis()

        logger.info { "Finished processing ${pendingInvoices.size} invoices in ${end - start} ms" }

        logger.info { "Successfully processed ${paid.size} invoices" }
        logger.info { "Failed processing ${failed.size} invoices" }
    }

    fun processInvoice(invoiceId: Int): InvoicePayment {
        val invoice = invoiceService.fetch(invoiceId)

        if (invoice.status == InvoiceStatus.PENDING) {
            return processInvoice(invoice)
        }
        throw InvoiceNotPendingException(invoice.id)

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

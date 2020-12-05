package io.pleo.antaeus.core.services.billing

import io.pleo.antaeus.core.environment.EnvironmentProvider
import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.InvoiceNotPendingException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.CurrencyProvider
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.core.scheduler.Scheduler
import io.pleo.antaeus.core.services.CustomerService
import io.pleo.antaeus.core.services.InvoiceService
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}


class BillingService(
    private val paymentProvider: PaymentProvider,
    private val currencyProvider: CurrencyProvider,
    private val invoiceService: InvoiceService,
    private val customerService: CustomerService,
    private val scheduler: Scheduler,
    environmentProvider: EnvironmentProvider
) {

    private val processInvoiceRetryCount = environmentProvider.getEnvVariable("PROCESS_INVOICE_RETRY_COUNT")
        .toInt()
    private val networkTimeoutOnError = environmentProvider.getEnvVariable("NETWORK_TIMEOUT_ON_ERROR_SECONDS").toLong()

    fun startAutomaticBilling() {
        logger.info { "Starting automatic billing, scheduling dailyBillingCheck" }
        val now = LocalDateTime.now()
        val firstOfNextMonth = now
            .plusMonths(1)
            .withDayOfMonth(1)
            .withHour(0)
            .withMinute(1)
        scheduler.scheduleJob(dailyBillingCheck, firstOfNextMonth)
    }

    private val dailyBillingCheck: () -> Unit = {
        logger.info { "Daily billing check" }
        val now = LocalDateTime.now()
        // process invoices only if today is 1st day of the month
        if (now.dayOfMonth == 1) {
            processInvoices()
        }
    }

    private fun processInvoices() {
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

    fun processInvoice(invoice: Invoice, attempt: Int = 0): InvoicePaymentAction {
        if (attempt >= processInvoiceRetryCount) {
            logger.warn { "Invoice processing attempt limit reached for invoice '${invoice.id}'" }
            return InvoicePaymentAction(invoiceService.markFailed(invoice.id), false)
        }

        return try {
            val processedInvoice = chargeInvoice(verifyInvoiceStatus(invoice))
            InvoicePaymentAction(processedInvoice, processedInvoice.status == InvoiceStatus.PAID)
        } catch (cnfe: CustomerNotFoundException) {
            logger.warn(cnfe) { "Customer '${invoice.customerId}' not found" }
            InvoicePaymentAction(invoiceService.markFailed(invoice.id), false)
        } catch (cme: CurrencyMismatchException) {
            logger.warn(cme) { "Currency does not match for invoice '${invoice.id}'. Trying currency conversion..." }
            processInvoice(convertInvoiceCurrency(invoice), attempt + 1)
        } catch (ne: NetworkException) {
            logger.warn(ne) { "Network error while processing invoice '${invoice.id}'. Retrying..." }
            runBlocking {
                retryPaymentAttempt(invoice, attempt)
            }
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

    private fun convertInvoiceCurrency(invoice: Invoice, attempt: Int = 0): Invoice {
        if (attempt >= processInvoiceRetryCount) {
            logger.warn { "Currency conversion attempt limit reached for invoice '${invoice.id}'" }
            return invoice
        }

        return try {
            val customer = customerService.fetch(invoice.customerId)
            val convertedAmount = currencyProvider.convert(invoice.amount, customer.currency)
            invoice.copy(amount = convertedAmount)
        } catch (ne: NetworkException) {
            logger.warn(ne) { "Network error while converting currency for invoice '${invoice.id}'. Retrying..." }
            runBlocking {
                retryCurrencyConvert(invoice, attempt)
            }
        }
    }

    private suspend fun retryCurrencyConvert(invoice: Invoice, attempt: Int = 0): Invoice {
        delay(networkTimeoutOnError)
        return convertInvoiceCurrency(invoice, attempt + 1)
    }

    private suspend fun retryPaymentAttempt(invoice: Invoice, attempt: Int = 0): InvoicePaymentAction {
        delay(networkTimeoutOnError)
        return processInvoice(invoice, attempt + 1)
    }
}

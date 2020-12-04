package io.pleo.antaeus.core.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.InvoiceNotPendingException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.core.scheduler.Scheduler
import io.pleo.antaeus.models.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal


private val MONEY_EUR = Money(BigDecimal(100), Currency.EUR)

private val INVOICE_PENDING = Invoice(1, 1, MONEY_EUR, InvoiceStatus.PENDING)
private val INVOICE_PROCESSING = Invoice(1, 1, MONEY_EUR, InvoiceStatus.PROCESSING)
private val INVOICE_PAID = Invoice(1, 1, MONEY_EUR, InvoiceStatus.PAID)
private val INVOICE_FAILED = Invoice(1, 1, MONEY_EUR, InvoiceStatus.FAILED)

class BillingServiceTest {


    private val paymentProvider = mockk<PaymentProvider>() {
        every { charge(INVOICE_PENDING) } returns true
        every { charge(INVOICE_PROCESSING) } returns true
        every { charge(INVOICE_PAID) } returns true
        every { charge(INVOICE_FAILED) } returns false
    }

    private val invoiceService = mockk<InvoiceService>() {
        every { markProcessing(INVOICE_PENDING.id) } returns INVOICE_PROCESSING
        every { markPaid(INVOICE_PROCESSING.id) } returns INVOICE_PAID
        every { markFailed(INVOICE_PROCESSING.id) } returns INVOICE_FAILED
    }

    private val scheduler = mockk<Scheduler> { }


    private val billingService = BillingService(
        paymentProvider = paymentProvider,
        invoiceService = invoiceService,
        scheduler = scheduler
    )

    @Test
    fun `will only process pending invoices`() {
        assertThrows<InvoiceNotPendingException> {
            billingService.processInvoice(INVOICE_PAID)
        }
    }

    @Test
    fun `will return failed invoice payment if charge fails`() {
        every { paymentProvider.charge(any()) } returns false

        val invoicePayment = billingService.processInvoice(INVOICE_PENDING)
        Assertions.assertEquals(InvoiceStatus.FAILED, invoicePayment.invoice.status)
    }

    @Test
    fun `will return paid invoice payment if charge ok`() {

        val invoicePayment = billingService.processInvoice(INVOICE_PENDING)
        Assertions.assertEquals(InvoiceStatus.PAID, invoicePayment.invoice.status)
    }

    @Test
    fun `will mark processing before charge and paid after on success`() {
        billingService.processInvoice(INVOICE_PENDING)
        verifyOrder {
            invoiceService.markProcessing(INVOICE_PENDING.id)
            paymentProvider.charge(INVOICE_PROCESSING)
            invoiceService.markPaid(INVOICE_PROCESSING.id)
        }
    }

    @Test
    fun `will mark processing before charge and failed after failure`() {
        every { paymentProvider.charge(any()) } returns false
        billingService.processInvoice(INVOICE_PENDING)
        verifyOrder {
            invoiceService.markProcessing(INVOICE_PENDING.id)
            paymentProvider.charge(INVOICE_PROCESSING)
            invoiceService.markFailed(INVOICE_PROCESSING.id)
        }
    }

    @Test
    fun `will call transition invoice to processing and charge`() {
        billingService.processInvoice(INVOICE_PENDING)
        verify { paymentProvider.charge(INVOICE_PROCESSING) }
    }

    @Test
    fun `will retry processing invoice processInvoiceRetryCount-times before quiting on network error`() {
        every { paymentProvider.charge(any()) } throws NetworkException()
        billingService.processInvoice(INVOICE_PENDING)

        verify(exactly = billingService.processInvoiceRetryCount) { paymentProvider.charge(any()) }

    }

    @Test
    fun `will return failed invoice payment on network error`() {
        every { paymentProvider.charge(any()) } throws NetworkException()
        val invoicePayment = billingService.processInvoice(INVOICE_PENDING)

        Assertions.assertFalse(invoicePayment.charged)
        Assertions.assertEquals(InvoiceStatus.FAILED, invoicePayment.invoice.status)
    }

    @Test
    fun `will not retry processing invoice if customer not found`() {
        every { paymentProvider.charge(any()) } throws CustomerNotFoundException(2)
        billingService.processInvoice(INVOICE_PENDING)

        verify(exactly = 1) { paymentProvider.charge(any()) }
    }

    @Test
    fun `will return failed invoice payment when customer not found`() {
        every { paymentProvider.charge(any()) } throws CustomerNotFoundException(2)
        val invoicePayment = billingService.processInvoice(INVOICE_PENDING)

        Assertions.assertFalse(invoicePayment.charged)
        Assertions.assertEquals(InvoiceStatus.FAILED, invoicePayment.invoice.status)
    }
}

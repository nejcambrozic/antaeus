package io.pleo.antaeus.core.services.billing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
import io.pleo.antaeus.models.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

private val CUSTOMER_EUR = Customer(1, Currency.EUR)
private val CUSTOMER_DKK = Customer(2, Currency.DKK)

private val MONEY_EUR = Money(BigDecimal(100), Currency.EUR)
private val MONEY_DKK = Money(BigDecimal(1000), Currency.DKK)

private val INVOICE_PENDING = Invoice(1, CUSTOMER_EUR.id, MONEY_EUR, InvoiceStatus.PENDING)
private val INVOICE_PROCESSING = Invoice(1, CUSTOMER_EUR.id, MONEY_EUR, InvoiceStatus.PROCESSING)
private val INVOICE_PAID = Invoice(1, CUSTOMER_EUR.id, MONEY_EUR, InvoiceStatus.PAID)
private val INVOICE_FAILED = Invoice(1, CUSTOMER_EUR.id, MONEY_EUR, InvoiceStatus.FAILED)
private val INVOICE_CURRENCY_MISMATCH = Invoice(2, CUSTOMER_EUR.id, MONEY_DKK, InvoiceStatus.PENDING)

class BillingServiceTest {


    private val paymentProvider = mockk<PaymentProvider> {
        every { charge(INVOICE_PENDING) } returns true
        every { charge(INVOICE_PROCESSING) } returns true
        every { charge(INVOICE_PAID) } returns true
        every { charge(INVOICE_FAILED) } returns false
        every { charge(INVOICE_CURRENCY_MISMATCH) } throws CurrencyMismatchException(
            INVOICE_CURRENCY_MISMATCH.id,
            INVOICE_CURRENCY_MISMATCH.customerId
        )
        // state of currency mismatch invoice after converting currency
        every { charge(INVOICE_CURRENCY_MISMATCH.copy(amount = MONEY_EUR)) } returns true
    }

    private val currencyProvider = mockk<CurrencyProvider> {
        every { convert(any(), Currency.DKK) } returns MONEY_DKK
        every { convert(any(), Currency.EUR) } returns MONEY_EUR
    }

    private val invoiceService = mockk<InvoiceService> {
        every { markProcessing(INVOICE_PENDING.id) } returns INVOICE_PROCESSING
        every { markPaid(INVOICE_PROCESSING.id) } returns INVOICE_PAID
        every { markFailed(INVOICE_PROCESSING.id) } returns INVOICE_FAILED
        // duplicated mock functions for currency mismatch invoice
        every { markProcessing(INVOICE_CURRENCY_MISMATCH.id) } returns INVOICE_CURRENCY_MISMATCH
        every { markFailed(INVOICE_CURRENCY_MISMATCH.id) } returns INVOICE_CURRENCY_MISMATCH.copy(status = InvoiceStatus.FAILED)
        every { markPaid(INVOICE_CURRENCY_MISMATCH.id) } returns INVOICE_CURRENCY_MISMATCH.copy(status = InvoiceStatus.PAID)
    }

    private val customerService = mockk<CustomerService> {
        every { fetch(1) } returns CUSTOMER_EUR
        every { fetch(2) } returns CUSTOMER_DKK
    }

    private val environmentProvider = mockk<EnvironmentProvider> {
        every { getEnvVariable("PROCESS_INVOICE_RETRY_COUNT") } returns "3"
        every { getEnvVariable("NETWORK_TIMEOUT_ON_ERROR_SECONDS") } returns "0"
    }

    private val scheduler = mockk<Scheduler> { }


    private val billingService = BillingService(
        paymentProvider = paymentProvider,
        currencyProvider = currencyProvider,
        invoiceService = invoiceService,
        customerService = customerService,
        environmentProvider = environmentProvider,
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

        verify(exactly = 3) { paymentProvider.charge(any()) }

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

    @Test
    fun `will attempt to charge, convert currency and retry charge`() {
        billingService.processInvoice(INVOICE_CURRENCY_MISMATCH)

        verifyOrder {
            paymentProvider.charge(INVOICE_CURRENCY_MISMATCH)
            currencyProvider.convert(INVOICE_CURRENCY_MISMATCH.amount, CUSTOMER_EUR.currency)
        }
        paymentProvider.charge(INVOICE_CURRENCY_MISMATCH.copy(amount = MONEY_EUR))
    }

    @Test
    fun `will fail to process invoice if can't convert currency`() {
        every { currencyProvider.convert(any(), any()) } throws NetworkException()

        val invoicePayment = billingService.processInvoice(INVOICE_CURRENCY_MISMATCH)

        Assertions.assertFalse(invoicePayment.charged)
        Assertions.assertEquals(InvoiceStatus.FAILED, invoicePayment.invoice.status)
    }

    @Test
    fun `will retry converting currency processInvoiceRetryCount**2 times before quiting on network error`() {
        every { currencyProvider.convert(any(), any()) } throws NetworkException()
        billingService.processInvoice(INVOICE_CURRENCY_MISMATCH)

        val totalExpectedConvertCalls = 9

        verify(exactly = 3) { paymentProvider.charge(any()) }
        verify(exactly = totalExpectedConvertCalls) { currencyProvider.convert(any(), any()) }
        // Each top-level invoiceProcessing attempt includes the same amount of convertCurrency attempts for each
        verifyOrder {
            paymentProvider.charge(any())
            currencyProvider.convert(any(), any())
            currencyProvider.convert(any(), any())
            currencyProvider.convert(any(), any())
            paymentProvider.charge(any())
            currencyProvider.convert(any(), any())
            currencyProvider.convert(any(), any())
            currencyProvider.convert(any(), any())
            paymentProvider.charge(any())
            currencyProvider.convert(any(), any())
            currencyProvider.convert(any(), any())
            currencyProvider.convert(any(), any())
        }
    }


}

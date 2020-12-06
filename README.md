## Antaeus

Antaeus (/ænˈtiːəs/), in Greek mythology, a giant of Libya, the son of the sea god Poseidon and the Earth goddess Gaia. He compelled all strangers who were passing through the country to wrestle with him. Whenever Antaeus touched the Earth (his mother), his strength was renewed, so that even if thrown to the ground, he was invincible. Heracles, in combat with him, discovered the source of his strength and, lifting him up from Earth, crushed him to death.

Welcome to our challenge.

## The solution

With me not having Kotlin experience, I wanted to focus more on writing clean, testable and readable code with reasonable class and function responsibilities, rather than trying to dive deep into the most Kotlin idiomatic way of writing code.
Total time spent: about 13 hours (includes googling about Kotlin basics: if statement syntax, scheduling options, threads and delays). Split roughly into 4 3-hour evening coding sessions with morning cleanup and this write-up.

### General approach and chunks of work

1. Getting familiar with the codebase, trying to run it, understanding the challenge and coming up with a plan. [1908d2de]
2. Implement happy flow of invoice processing and expose this via REST API for testing. [2183f638, 20a02ba8]
3. Get some scheduling logic working (at 1 minute intervals, so I could see it running). [195b84bc]
4. Feeling confident about general design decisions I made: adding tests. In an ideal case I would add them while developing the solution, but for multiple reasons (not being familiar with Kotlin and it's testing frameworks, task being very open-ended so I didn't have complete design in mind) I decided to postpone until now (to I get some very basic things down first). [f72e0b39]
5. Handling error flows with tests (retries on network, customer not found, currency mismatch) [e755d29d, 43ad8688, be12c6a0]
6. Fixing scheduling logic to only process invoices on 1st day of the month. [5664e71b]
7. Code improvements (read variables from environment, more logging, etc). [78f695db, ef5c3020]
8. Documentation [`git rev-parse --short HEAD`]

### Architecture

I ended up just expanding the architecture set in place by the challenge itself. I saw no reason to change it for the scale of the required modifications. There are a couple of additions, though:

* moved `BillingService` to `io.pleo.antaeus.core.services.billing` package, since I added `InvoicePaymentAction` data class
* `io.pleo.antaeus.core.scheduler.Scheduler` that is used to schedule jobs
* `io.pleo.antaeus.core.environment.EnvironmentProvider` which is responsible for interacting with environment variables

### Scheduling

I quickly discovered there is no out of the box way to schedule a job for 1st day of the Month repeatedly. Instead, I opted for a daily job that checks if today is the 1st day of the month:
```kotlin
private val dailyBillingCheck: () -> Unit = {
    logger.info { "Daily billing check" }
    val now = LocalDateTime.now()
    // process invoices only if today is 1st day of the month
    if (now.dayOfMonth == 1) {
        processInvoices()
    }
}
```
As a minor optimization (and because I wanted to play a bit with Kotlin's `LocalDateTime`) the first `dailyBillingCheck` is scheduled on the first day of the next month:
```kotlin
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
```
This should give us a pretty fail-safe approach to make sure invoice processing indeed runs on desired days. Although I am pretty sure there is an edge case there if we deploy a new version of the service at midnight on the 1st day of the month and previously scheduled billing did not execute yet, I decided not to care about this for now.

### Invoice processing

We only want to process invoices with `PENDING` state, function responsible for processing invoices is quite simple:

```kotlin
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
```

After processing, we simply log the results of the processing. A major improvement here that would be required in real life is to integrate with some notification service. This could be used for a couple of things:
* send emails to customers with status of their invoice: paid/failed
* send analytics data about the entire job to team owning the billing (number of errors and their type, how many invoices failed/were processed, etc.)
* send Slack notifications when starting to process invoices

I have designed the single invoice processing flow:
1. verify invoice is in the `PENDING` state
2. mark invoice as `PROCESSING`
3. call `PaymentProvider` to charge the invoice
    * on success: mark invoice as `PAID` and return
    * on fail: mark invoice as `FAILED` and return (eg. this happens when customer doesn't have enough funds to pay the invoice)
    * on error:
        * `CustomerNotFoundException` - mark invoice as `FAILED` and return
        * `CurrencyMismatchException` - convert invoice currency flow and retry invoice processing
        * `NetworkException` - retry invoice processing

Invoice processing returns `InvoicePaymentAction`:
```kotlin
data class InvoicePaymentAction(val invoice: Invoice, val charged: Boolean)
```
It holds the `invoice` with appropriately updated status based on the result of payment action, and `charged` that indicates if the charge was successful.

### Currency conversion

This seemed like a very common use case, so I wanted to handle it and try to convert the currency and retry payment. I decided to add `io.pleo.antaeus.core.external.CurrencyProvider`:

```kotlin
interface CurrencyProvider {
    /*
        Convert provided money to specified currency

        Returns:
          `Money` in specified currency


        Throws:
          `NetworkException`: when a network error happens.
     */
    fun convert(from: Money, to: Currency): Money
}
```
To follow design principle of provided `PaymentProvider` I decided to include an option of this provider to throw `NetworkException` and handle that in my service. Logic to handle this network error is the same as when it happens with `PaymentProvider` - retry.

### Limiting retries, circuit breaking and environment variables

Every time when implementing retry logic there is a need to limit it to avoid infinite loops. I decided to limit all retries with an environment variable `PROCESS_INVOICE_RETRY_COUNT`.
On network errors it wouldn't make the most sense to immediately retry as chances are network is still unstable. To control what the delay before retrying an action that previously cause network exception I introduced an environment variable: `NETWORK_TIMEOUT_ON_ERROR_SECONDS`

#### Enforcing retry limit number

Just a simple `if` statement, eg:
```kotlin
if (attempt >= processInvoiceRetryCount) {
    logger.warn { "Invoice processing attempt limit reached for invoice '${invoice.id}'" }
    return InvoicePaymentAction(invoiceService.markFailed(invoice.id), false)
}
```

#### Delay when retrying network actions

To implement delays in a thread safe way I discovered Kotlin's coroutines, which I decided to use:
```kotlin
runBlocking {
    retryPaymentAttempt(invoice, attempt)
}

private suspend fun retryPaymentAttempt(invoice: Invoice, attempt: Int = 0): InvoicePaymentAction {
    delay(networkTimeoutOnError)
    return processInvoice(invoice, attempt + 1)
}
```


#### EnvironmentProvider

The idea of `EnvironmentProvider` is to abstract interaction with environment (and potentially other external configuration methods) away from the services:
```kotlin
class EnvironmentProvider {

    fun getEnvVariable(name: String): String {
        return System.getenv(name)
    }
}
```
This also makes writing tests way easier, as we can just mock `getEnvVariable` function as any other without hacking around setting environment variables.

### REST API

I only added an endpoint to trigger processing of a single invoice: `/rest/v1/invoices/{:id}/process` which returns `409 Conflict` response if invoice is not in `PENDING` state, otherwise returns `InvoicePaymentAction`.  
I considered adding an "admin endpoint" to toggle automatic billing on and off, but since billing is a core functionality I couldn't think of a real world scenario where we would want to do this. I considered adding an endpoint to trigger processing of all invoices, but since I anticipate this to be very intensive action I decided against having a single endpoint for that. API still allows for manual triggering, but clients should first get the list of `PENDING` invoices and call process action one by one. In reality this is probably something we wouldn't want to do very often. 

## Developing

Requirements:
- \>= Java 11 environment

Open the project using your favorite text editor. If you are using IntelliJ, you can open the `build.gradle.kts` file and it is gonna setup the project in the IDE for you.

### Building

```
./gradlew build
```

### Running

There are 2 options for running Anteus. You either need libsqlite3 or docker. Docker is easier but requires some docker knowledge. We do recommend docker though.

*Running Natively*

Native java with sqlite (requires libsqlite3):

If you use homebrew on MacOS `brew install sqlite`.

```
./gradlew run
```

*Running through docker*

Install docker for your platform

```
docker build . -t antaeus
docker run antaeus
```

### App Structure
The code given is structured as follows. Feel free however to modify the structure to fit your needs.
```
├── buildSrc
|  | gradle build scripts and project wide dependency declarations
|  └ src/main/kotlin/utils.kt 
|      Dependencies
|
├── pleo-antaeus-app
|       main() & initialization
|
├── pleo-antaeus-core
|       This is probably where you will introduce most of your new code.
|       Pay attention to the PaymentProvider and BillingService class.
|
├── pleo-antaeus-data
|       Module interfacing with the database. Contains the database 
|       models, mappings and access layer.
|
├── pleo-antaeus-models
|       Definition of the Internal and API models used throughout the
|       application.
|
└── pleo-antaeus-rest
        Entry point for HTTP REST API. This is where the routes are defined.
```

### Main Libraries and dependencies
* [Exposed](https://github.com/JetBrains/Exposed) - DSL for type-safe SQL
* [Javalin](https://javalin.io/) - Simple web framework (for REST)
* [kotlin-logging](https://github.com/MicroUtils/kotlin-logging) - Simple logging framework for Kotlin
* [JUnit 5](https://junit.org/junit5/) - Testing framework
* [Mockk](https://mockk.io/) - Mocking library
* [Sqlite3](https://sqlite.org/index.html) - Database storage engine

Happy hacking 😁!

package io.pleo.antaeus.core.external

import io.pleo.antaeus.models.Currency
import io.pleo.antaeus.models.Money

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
package io.pleo.antaeus.core.environment

class EnvironmentProvider {

    fun getEnvVariable(name: String): String {
        return System.getenv(name)
    }
}
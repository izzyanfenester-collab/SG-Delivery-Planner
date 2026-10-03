package com.izzyan.sgdeliveryplanner

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.HttpException

/** Only app-authored English messages are safe to display directly. */
class PlannerError(val englishMessage: String, cause: Throwable? = null) :
    IllegalStateException(englishMessage, cause)

fun englishError(error: Throwable, fallback: String): String = when (error) {
    is PlannerError -> error.englishMessage
    is UnknownHostException, is ConnectException ->
        "Could not connect to the mapping service. Check your internet connection and try again."
    is SocketTimeoutException -> "The mapping service took too long to respond. Please try again."
    is SSLException -> "Could not make a secure connection to the mapping service. Please try again later."
    is HttpException -> when (error.code()) {
        401, 403 -> "The mapping service denied access. Check the routing server in Settings and try again."
        429 -> "The mapping service is busy. Wait a moment, then try again."
        in 500..599 -> "The mapping service is temporarily unavailable. Please try again later."
        else -> "The mapping service could not process this request. Check your postal codes and routing server in Settings."
    }
    else -> fallback
}

package edu.sustech.mobile.core

/**
 * Every service failure the app can raise.
 *
 * The three flags are the ones the UI has to branch on — everything else is
 * just a message to show:
 *  - [offCampus]: the service refused the request from outside the campus
 *    network (PMS answers 403 "Access forbidden").
 *  - [signInRequired]: the session is gone or was never established, so the
 *    screen should offer a sign-in action instead of a generic error.
 */
open class ApiException(
    message: String,
    val offCampus: Boolean = false,
    val signInRequired: Boolean = false,
) : Exception(message)

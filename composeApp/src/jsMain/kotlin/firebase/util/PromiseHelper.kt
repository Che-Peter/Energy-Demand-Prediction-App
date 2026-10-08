package firebase.util

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

suspend fun <T> Promise<T>.awaitPromise(): T =
    suspendCoroutine { cont ->
        then(
            onFulfilled = { value ->
                cont.resume(value)
            },
            onRejected = { error ->
                cont.resumeWithException(Throwable(error.toString()))
            }
        )
    }
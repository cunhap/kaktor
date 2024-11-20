package org.kaktor.utils

fun <T> Any?.attempt(block: () -> T): Result<T> {
    return try {
        val result = block()
        Result.success(result)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
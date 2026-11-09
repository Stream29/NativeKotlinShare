package org.jetbrains.kotlin.native.d1.core

import kotlin.jvm.JvmInline

@JvmInline
public value class D1Tag(public val raw: String) {
    public fun normalized(): String = raw.trim().lowercase()
}

public interface D1Processor<T> {
    public val name: String
    public fun process(input: T, tag: D1Tag): String
}

public data class D1Envelope<T>(
    public val id: Int,
    public val tag: D1Tag,
    public val payload: T,
) {
    public fun <R> mapPayload(transform: (T) -> R): D1Envelope<R> =
        D1Envelope(id = id, tag = tag, payload = transform(payload))
}

public open class D1DomainException(
    public val code: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class D1ValidationException(
    public val field: String,
    code: Int,
    message: String,
    cause: Throwable? = null,
) : D1DomainException(code, "$field:$message", cause)

public inline fun <T> d1GuardInline(
    label: String,
    block: () -> T,
): Result<Pair<String, T>> {
    return try {
        Result.success(label to block())
    } catch (e: D1DomainException) {
        Result.failure(D1DomainException(e.code + 1000, "guarded($label)->${e.message}", e))
    }
}

public class D1IntProcessor(
    override val name: String,
    private val multiplier: Int,
) : D1Processor<Int> {
    override fun process(input: Int, tag: D1Tag): String {
        if (input < 0) {
            throw D1ValidationException("input", 400, "negative($input)")
        }
        val scaled = input * multiplier
        return "${name}[${tag.normalized()}]=${scaled}"
    }
}

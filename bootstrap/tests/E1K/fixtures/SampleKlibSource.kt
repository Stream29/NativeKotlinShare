package e1k.sample.pkg

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPE_PARAMETER,
)
annotation class E1KTag(
    val label: String,
    val code: Int = 7,
    val flags: IntArray = [1, 2],
)

annotation class E1KInternalMarker

enum class E1KMode(val factor: Int) {
    FAST(1),
    SAFE(2),
    CUSTOM(7),
}

interface E1KService<out T> {
    fun compute(seed: Int): T
    fun describe(): String = "e1k-service"
}

@E1KTag(label = "base-box", code = 42, flags = [3, 5, 8])
open class E1KBaseBox<@E1KTag("type-param") T : CharSequence>(
    val item: T,
) : E1KService<Int> {
    open var weight: Int = DEFAULT_WEIGHT

    constructor(item: T, bonus: Int) : this(item) {
        weight += bonus
    }

    override fun compute(@E1KTag("param-seed") seed: Int): Int =
        item.length + weight + seed + privateOffset()

    private fun privateOffset(): Int = 3

    class NestedHelper(val tag: String) {
        fun formatted(): String = "[$tag]"
    }

    inner class InnerInspector {
        fun inspect(): String = "${item.length}:$weight"
    }

    companion object {
        const val DEFAULT_WEIGHT: Int = 10
        const val ESCAPED_CONTROL_CHAR: Char = '\u0007'
        const val ESCAPED_TAB_CHAR: Char = '\t'
        const val ESCAPED_QUOTE_CHAR: Char = '\''
        const val ESCAPED_BACKSLASH_CHAR: Char = '\\'
        const val NORMAL_ASCII_CHAR: Char = 'Z'
    }
}

value class E1KToken(val raw: Long) {
    val isPositive: Boolean
        get() = raw > 0L
}

typealias E1KStringBox = E1KBaseBox<String>

const val E1K_VERSION: String = "2.4.20-e1k"

var globalAdjustment: Int = 5

@E1KInternalMarker
fun markedInternalApi(input: Int): Int = input + globalAdjustment

inline fun <T> withE1KMultiplier(base: Int, factor: Int = 3, block: (Int) -> T): T {
    return block(base * factor + globalAdjustment)
}

inline fun <reified R : Any> classifyE1K(value: Any?): String {
    return if (value is R) R::class.simpleName ?: "matched" else "other"
}

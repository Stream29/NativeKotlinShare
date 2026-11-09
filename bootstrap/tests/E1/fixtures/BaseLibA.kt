package e1.fixture.base

interface ComputeNode<T> {
    val label: String
    fun transform(input: T): T
}

class LinearNode(
    override val label: String,
    private val scale: Int,
    private val offset: Int,
) : ComputeNode<Int> {
    override fun transform(input: Int): Int = input * scale + offset
    internal fun internalSecretBias(): Int = scale * 100 + offset
}

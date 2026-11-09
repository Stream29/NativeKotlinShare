package e1.fixture.base

inline fun <T> runChain(seed: T, nodes: List<ComputeNode<T>>, onStep: (String, T) -> Unit): T {
    var acc = seed
    for (node in nodes) {
        acc = node.transform(acc)
        onStep(node.label, acc)
    }
    return acc
}

fun summarizeNodes(seed: Int): String {
    val n1 = LinearNode("alpha", 3, 2)
    val n2 = LinearNode("beta", 4, -5)
    val log = mutableListOf<String>()
    val total = runChain(seed, listOf(n1, n2)) { label, value ->
        log += "$label=$value"
    }
    return "total=$total;steps=${log.joinToString(",")};secret=${n1.internalSecretBias() + n2.internalSecretBias()}"
}

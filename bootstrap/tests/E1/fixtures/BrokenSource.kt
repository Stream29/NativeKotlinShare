package e1.fixture.broken

fun brokenCompute(x: Int): String {
    val missing = nonExistentHelper(x)
    return x + 1
}

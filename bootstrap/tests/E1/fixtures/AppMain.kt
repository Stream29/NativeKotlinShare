package e1.fixture.app

import e1.fixture.base.LinearNode
import e1.fixture.base.summarizeNodes

fun main() {
    val summary = summarizeNodes(7)
    val friendNode = LinearNode("gamma", 5, 9)
    println("summary=$summary")
    println("friendSecret=${friendNode.internalSecretBias()}")
    println("E1_STAGE_APP_OK")
}

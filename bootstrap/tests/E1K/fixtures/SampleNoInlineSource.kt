package e1k.sample.noinline

class PlainRecord(val id: Int, val name: String)

fun formatRecord(record: PlainRecord): String = "${record.id}:${record.name}"

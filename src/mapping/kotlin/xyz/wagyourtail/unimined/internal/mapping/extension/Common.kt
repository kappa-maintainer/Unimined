package xyz.wagyourtail.unimined.internal.mapping.extension

fun splitMethodNameAndDescriptor(nameAndDescriptor: String): Pair<String, String?> {
    val mName = nameAndDescriptor.substringBefore("(").substringAfter(";")
    val desc = if (nameAndDescriptor.contains("(")) "(${nameAndDescriptor.substringAfter("(")}" else null
    return Pair(mName, desc)
}

/**
 * The owner of a member reference in descriptor form, e.g. `net/minecraft/class_761` for
 * `Lnet/minecraft/class_761;method_3279()V`, or null when the reference is not owner-qualified.
 */
fun ownerOfMemberReference(reference: String): String? {
    if (!reference.startsWith("L") || !reference.contains(";")) return null
    return reference.substring(1, reference.indexOf(';'))
}

fun splitFieldNameAndDescriptor(nameAndDescriptor: String): Pair<String, String?> {
    val fName = nameAndDescriptor.substringBefore(":").substringAfter(";")
    val desc = if (nameAndDescriptor.contains(":")) nameAndDescriptor.substringAfter(":") else null
    return Pair(fName, desc)
}
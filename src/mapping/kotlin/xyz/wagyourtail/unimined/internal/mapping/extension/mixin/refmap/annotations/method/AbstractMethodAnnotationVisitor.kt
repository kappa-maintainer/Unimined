package xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.annotations.method

import net.fabricmc.tinyremapper.extension.mixin.common.ResolveUtility
import net.fabricmc.tinyremapper.extension.mixin.common.data.AnnotationElement
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.ownerOfMemberReference
import xyz.wagyourtail.unimined.internal.mapping.extension.splitMethodNameAndDescriptor
import xyz.wagyourtail.unimined.util.orElseOptional
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

abstract class AbstractMethodAnnotationVisitor(
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor,
    methodAccess: Int,
    protected val methodName: String,
    protected val methodDescriptor: String,
    protected val methodSignature: String?,
    methodExceptions: Array<out String>?,
    protected val refmapBuilder: RefmapBuilderClassVisitor
) : AnnotationVisitor(Constant.ASM_VERSION, parent) {

    abstract val annotationName: String

    protected val remap = AtomicBoolean(refmapBuilder.remap.get())
    protected var targetNames = mutableListOf<String>()

    protected val resolver = refmapBuilder.resolver
    protected val logger = refmapBuilder.logger
    protected val existingMappings = refmapBuilder.existingMappings
    protected val mapper = refmapBuilder.mapper
    protected val refmap = refmapBuilder.refmap
    protected val mixinName = refmapBuilder.mixinName
    protected val targetClasses = refmapBuilder.targetClasses
    protected val allowImplicitWildcards = refmapBuilder.allowImplicitWildcards
    protected val noRefmap = refmapBuilder.mixinRemapExtension.noRefmap.contains("BaseMixin")

    override fun visit(name: String?, value: Any) {
        super.visit(name, value)
        if (name == AnnotationElement.REMAP) remap.set(value as Boolean)
    }

    open fun getTargetNameAndDescs(targetMethod: String, wildcard: Boolean): Pair<String, Set<String?>> {
        val targetDescs = setOf(if (targetMethod.contains("(")) {
            "(" + targetMethod.substringAfter("(")
        } else {
            null
        })
        val targetName = if (wildcard) {
            targetMethod.substringBefore("*")
        } else {
            targetMethod.substringBefore("(")
        }
        return targetName to targetDescs
    }

    open fun remapTargetNames(noRefmapAcceptor: (String) -> Unit) {
        if (remap.get()) {
            outer@for (targetMethod in targetNames) {
                if (targetMethod == "<init>" || targetMethod == "<clinit>" ||
                    targetMethod == "<init>*"
                ) {
                    noRefmapAcceptor(targetMethod)
                    continue
                }
                val wildcard = targetMethod.endsWith("*")
                val (targetName, targetDescs) = getTargetNameAndDescs(targetMethod, wildcard)
                val qualifiedOwner = ownerOfMemberReference(targetName)
                val targetClasses = if (qualifiedOwner != null) {
                    if (qualifiedOwner !in targetClasses) {
                        logger.warn("Target class $qualifiedOwner not in target classes for mixin $mixinName, this seems wrong!")
                    }
                    setOf(qualifiedOwner)
                } else {
                    this.targetClasses
                }
                // The entry a refmap has for an owner-qualified selector (Loom writes those as
                // `L<owner>;<name><desc>`) carries its owner in the mod's prod namespace (intermediary),
                // which is the namespace the mapping tree knows. The owner written in the selector itself
                // is in the mod's `named` namespace (yarn), which the tree cannot resolve, so resolve
                // against the refmap's owner instead of leaving the selector untranslated.
                val existing = existingMappings[targetMethod]
                val existingOwner = if (qualifiedOwner != null) ownerOfMemberReference(existing ?: "") else null
                for (targetDesc in targetDescs) {
                    var implicitWildcard = targetDesc == null && allowImplicitWildcards
                    for (targetClass in targetClasses) {
                        val resolveClass = existingOwner ?: targetClass
                        val target = resolver.resolveMethod(
                            resolveClass,
                            targetName.substringAfter(";"),
                            targetDesc,
                            (if (wildcard || implicitWildcard) ResolveUtility.FLAG_FIRST else ResolveUtility.FLAG_UNIQUE) or ResolveUtility.FLAG_RECURSIVE
                        ).orElseOptional {
                            existing?.let { existingEntry ->
                                logger.info("Remapping using existing mapping for $targetMethod: $existingEntry")
                                if (existingEntry.endsWith("*")) {
                                    val mname = existingEntry.substringAfter(";").let { it.substring(0, it.length - 1 ) }
                                    resolver.resolveMethod(
                                        resolveClass,
                                        mname,
                                        null,
                                        ResolveUtility.FLAG_FIRST or ResolveUtility.FLAG_RECURSIVE
                                    )
                                } else {
                                    val (mName, mDesc) = splitMethodNameAndDescriptor(existingEntry)
                                    if (mDesc == null && allowImplicitWildcards) {
                                        implicitWildcard = true
                                    }
                                    resolver.resolveMethod(
                                        resolveClass,
                                        mName,
                                        mDesc,
                                        (if (implicitWildcard) ResolveUtility.FLAG_FIRST else ResolveUtility.FLAG_UNIQUE) or ResolveUtility.FLAG_RECURSIVE
                                    )
                                }
                            } ?: Optional.empty()
                        }
                        target.ifPresent { targetVal ->
                            val mappedClass = resolver.resolveClass(resolveClass)
                                .map { mapper.mapName(it) }
                                .orElse(resolveClass)
                            val mappedName = mapper.mapName(targetVal)
                            val mappedDesc = /* if (implicitWildcard) "" else */  if (wildcard && mappedName != "<clinit>") "*" else mapper.mapDesc(targetVal)
                            if (targetClasses.size > 1) {
                                refmap.addProperty(targetMethod, "$mappedName$mappedDesc")
                                noRefmapAcceptor("$mappedName$mappedDesc")
                            } else {
                                refmap.addProperty(targetMethod, "L$mappedClass;$mappedName$mappedDesc")
                                noRefmapAcceptor("L$mappedClass;$mappedName$mappedDesc")
                            }
                        }

                        if (target.isPresent) continue@outer
                        else if (targetName == "*") {
                            // The descriptor classnames may still be possible to remap, even though a wildcard method
                            // name is provided
                            val mappedDesc = mapper.asTrRemapper().mapMethodDesc(targetDesc)
                            if (mappedDesc != targetDesc) {
                                val mappedClass = resolver.resolveClass(resolveClass)
                                    .map { mapper.mapName(it) }
                                    .orElse(resolveClass)
                                val mappedPrefix = if (targetClasses.size > 1) "L$mappedClass;*" else "*"
                                refmap.addProperty(targetMethod, "$mappedPrefix$mappedDesc")
                                noRefmapAcceptor("$mappedPrefix$mappedDesc")
                                continue@outer
                            }
                        }
                    }
                }
                logger.warn(
                    "Failed to resolve $annotationName $targetMethod ($targetDescs) on ($methodName$methodDescriptor) $methodSignature in $mixinName"
                )
                noRefmapAcceptor(targetMethod)
            }
        } else {
            for (targetMethod in targetNames) {
                noRefmapAcceptor(targetMethod)
            }
        }
    }

}
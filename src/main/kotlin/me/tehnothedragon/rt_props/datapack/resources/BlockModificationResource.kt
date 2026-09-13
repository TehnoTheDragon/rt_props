package me.tehnothedragon.rt_props.datapack.resources

import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import me.tehnothedragon.rt_props.RtProps
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.PreparableReloadListener
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagKey
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class BlockModificationResource : PreparableReloadListener {
    val ITEM_PREDICATE_CODEC = Codec.STRING.comapFlatMap({ string ->
        try {
            if (string.startsWith("#")) {
                val id = ResourceLocation.parse(string.substring(1))
                DataResult.success(ItemTagPredicate(ItemTags.create(id)))
            } else {
                DataResult.success(ItemIdPredicate(ResourceLocation.parse(string)))
            }
        } catch (e: Exception) {
            DataResult.error { "Invalid item predicate '$string': ${e.localizedMessage}" }
        }
    }, { predicate ->
        when (predicate) {
            is ItemTagPredicate -> "#${predicate.tag.location}"
            is ItemIdPredicate -> predicate.item.toString()
        }
    })
    val HARDNESS_MODIFICATION_CODEC: Codec<HardnessModification> = RecordCodecBuilder.create { instance ->
        instance.group(
            Codec.FLOAT.optionalFieldOf("default")
                .forGetter(HardnessModification::default),

            Codec.FLOAT.optionalFieldOf("else")
                .forGetter(HardnessModification::`else`),

            ITEM_PREDICATE_CODEC
                .listOf()
                .optionalFieldOf("tools")
                .forGetter(HardnessModification::tools)

        ).apply(instance, ::HardnessModification)
    }
    val FACTORS_MODIFICATION_CODEC: Codec<FactorsModification> = RecordCodecBuilder.create { instance ->
        instance.group(
            Codec.FLOAT.optionalFieldOf("speed")
                .forGetter(FactorsModification::speed),

            Codec.FLOAT.optionalFieldOf("jump")
                .forGetter(FactorsModification::jump),

        ).apply(instance, ::FactorsModification)
    }
    val BLOCK_MODIFICATION_CODEC: Codec<BlockModification> = RecordCodecBuilder.create { instance ->
        instance.group(
            HARDNESS_MODIFICATION_CODEC.optionalFieldOf("hardness")
                .forGetter(BlockModification::hardness),
            FACTORS_MODIFICATION_CODEC.optionalFieldOf("factors")
                .forGetter(BlockModification::factors),
            Codec.FLOAT.optionalFieldOf("friction")
                .forGetter(BlockModification::friction)
        ).apply(instance, ::BlockModification)
    }

    private val pendingBlocks = mutableListOf<PendingBlockModification>()
    private val pendingTags = mutableListOf<PendingBlockModification>()

    override fun reload(
        barrier: PreparableReloadListener.PreparationBarrier,
        resourceManager: ResourceManager,
        preparationProfiler: ProfilerFiller,
        reloadProfiler: ProfilerFiller,
        backgroundExecutor: Executor,
        gameExecutor: Executor
    ): CompletableFuture<Void?> {
        return CompletableFuture.supplyAsync({
            val resources = resourceManager.listResources(RESOURCE_ID) {
                it.path.endsWith(".json")
            }

            for ((location, resource) in resources) {
                val target = getTarget(location) ?: continue
                val pending = when (target) {
                    is Target.Block -> pendingBlocks
                    is Target.Tag -> pendingTags
                }

                try {
                    resource.open().use { inputStream ->
                        val reader = InputStreamReader(
                            inputStream,
                            StandardCharsets.UTF_8
                        )

                        val json = JsonParser.parseReader(reader)
                        BLOCK_MODIFICATION_CODEC
                            .parse(JsonOps.INSTANCE, json)
                            .resultOrPartial { error ->
                                RtProps.LOGGER.error("Failed to parse block modification ${location}: $error")
                            }
                            .ifPresent { modification ->
                                pending += PendingBlockModification(
                                    target,
                                    modification
                                )
                            }
                    }
                } catch (e: Exception) {
                    RtProps.LOGGER.error("Failed to load block modification ${location}: $e")
                }
            }

            Pair(pendingTags, pendingBlocks)
        }, backgroundExecutor)
            .thenCompose(barrier::wait)
            .thenAcceptAsync({ pending ->

            }, gameExecutor)
    }

    fun rebuild(registryLookup: RegistryAccess) {
        val modifications = mutableMapOf<Block, BlockModification>()

        val blockLookup = registryLookup
            .lookupOrThrow(Registries.BLOCK)

        for (entry in pendingTags) {
            if (entry.target is Target.Tag) {
                val tagKey = TagKey.create(Registries.BLOCK, entry.target.id)
                blockLookup.get(tagKey).ifPresent { holders ->
                    for (holder in holders) {
                        modifications[holder.value()] = entry.modification
                    }
                }
            }
        }

        for (entry in pendingBlocks) {
            if (entry.target is Target.Block) {
                if (BuiltInRegistries.BLOCK.containsKey(entry.target.id)) {
                    val block = BuiltInRegistries.BLOCK
                        .get(entry.target.id)
                    modifications[block] = entry.modification
                } else {
                    RtProps.LOGGER.error("block ${entry.target.id} does not exist")
                }
            }
        }

        storage = modifications

        RtProps.LOGGER.info("Loaded ${modifications.size} block modifications")
    }

    sealed interface InternalItemPredicate {
        fun matches(stack: ItemStack): Boolean
    }

    data class ItemTagPredicate(
        val tag: TagKey<Item>
    ) : InternalItemPredicate {
        override fun matches(stack: ItemStack): Boolean {
            return stack.`is`(tag)
        }
    }

    data class ItemIdPredicate(
        val item: ResourceLocation
    ) : InternalItemPredicate {
        override fun matches(stack: ItemStack): Boolean {
            return BuiltInRegistries.ITEM.getKey(stack.item) == item
        }
    }

    data class BlockModification(
        val hardness: Optional<HardnessModification>,
        val factors: Optional<FactorsModification>,
        val friction: Optional<Float>,
    )

    data class FactorsModification(
        val speed: Optional<Float>,
        val jump: Optional<Float>,
    )

    data class HardnessModification(
        val default: Optional<Float>,
        val `else`: Optional<Float>,
        val tools: Optional<List<InternalItemPredicate>>
    )

    private sealed interface Target {
        data class Block(val id: ResourceLocation) : Target
        data class Tag(val id: ResourceLocation) : Target
    }

    private data class PendingBlockModification(
        val target: Target,
        val modification: BlockModification
    )

    private fun getTarget(resourceLocation: ResourceLocation): Target? {
        val path = resourceLocation.path

        var isTag: Boolean
        var prefix: String

        when {
            path.startsWith("$RESOURCE_ID/tags/") -> {
                isTag = true
                prefix = "$RESOURCE_ID/tags/"
            }
            path.startsWith("$RESOURCE_ID/blocks/") -> {
                isTag = false
                prefix = "$RESOURCE_ID/blocks/"
            }
            else -> return null
        }

        val relativePath = path.removePrefix(prefix)

        if (!relativePath.endsWith(".json")) {
            return null
        }

        val blockId = relativePath.removeSuffix(".json")
        val separator = blockId.indexOf("/")

        if (separator == -1) {
            return null
        }

        val targetId = ResourceLocation.fromNamespaceAndPath(
            blockId.take(separator),
            blockId.substring(separator + 1)
        )

        return when (isTag) {
            true -> Target.Tag(targetId)
            false -> Target.Block(targetId)
        }
    }

    companion object {
        const val RESOURCE_ID = "block_modification"
        private var storage: Map<Block, BlockModification> = emptyMap()
        fun getBlockModification(block: Block): BlockModification? = storage[block]
    }
}
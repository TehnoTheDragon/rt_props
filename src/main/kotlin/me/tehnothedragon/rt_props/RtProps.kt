package me.tehnothedragon.rt_props

import me.tehnothedragon.rt_props.datapack.resources.BlockModificationResource
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.AddReloadListenerEvent
import net.neoforged.neoforge.event.TagsUpdatedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull

@Mod(RtProps.ID)
object RtProps {
    const val ID = "rt_props"

    val LOGGER: Logger = LogManager.getLogger(ID)
    val blockModificationResource = BlockModificationResource()

    init {
        NeoForge.EVENT_BUS.addListener(RtProps::onBreakSpeed)
        NeoForge.EVENT_BUS.addListener(RtProps::onAddReload)
        NeoForge.EVENT_BUS.addListener(RtProps::onTagsUpdated)
    }

    @SubscribeEvent
    private fun onBreakSpeed(event: PlayerEvent.BreakSpeed) {
        val position = event.position.orElse(null) ?: return
        val state = event.state

        val modification = BlockModificationResource.getBlockModification(state.block) ?: return
        val hardness = modification.hardness.getOrNull() ?: return

        val player = event.entity
        val level = player.level()

        var overrideNewHardness: Float? = null

        if (hardness.tools.isPresent) {
            val matches = hardness.tools.get().any { predicate ->
                predicate.matches(player.mainHandItem)
            }
            if (!matches) {
                overrideNewHardness = hardness.`else`.getOrDefault(-1.0f)
            }
        }

        val newHardness = overrideNewHardness ?: hardness.default.getOrNull() ?: return
        val originalHardness = state.getDestroySpeed(level, position)

        if (newHardness <= 0.0f) {
            event.newSpeed = Float.MIN_VALUE
            return
        }

        if (originalHardness < 0.0f) {
            return
        }

        event.newSpeed = event.originalSpeed * originalHardness / newHardness
    }

    @SubscribeEvent
    private fun onAddReload(event: AddReloadListenerEvent) {
        event.addListener(blockModificationResource)
    }

    @SubscribeEvent
    private fun onTagsUpdated(event: TagsUpdatedEvent) {
        blockModificationResource.rebuild(event.registryAccess)
    }
}

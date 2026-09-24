package net.imprex.orebfuscator.obfuscation;

import dev.imprex.orebfuscator.PermissionRequirements;
import dev.imprex.orebfuscator.obfuscation.ObfuscationPipeline;
import dev.imprex.orebfuscator.statistics.InjectorStatistics;
import java.util.Set;
import net.imprex.orebfuscator.Orebfuscator;
import net.imprex.orebfuscator.iterop.BukkitChunkPacketAccessor;
import net.imprex.orebfuscator.iterop.BukkitPlayerAccessor;
import net.imprex.orebfuscator.iterop.BukkitPlayerAccessorManager;
import net.imprex.orebfuscator.iterop.BukkitWorldAccessor;
import net.imprex.orebfuscator.iterop.RawChunkDataPacket;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.PacketEvents;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketListenerAbstract;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketListenerPriority;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketReceiveEvent;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketSendEvent;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.protocol.packettype.PacketType;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChunkBatchAck;

/**
 * Obfuscation happens asynchronously, so the original chunk-data packet is cancelled here and a
 * hand-rolled raw copy ({@link RawChunkDataPacket}) is resent manually once obfuscation completes.
 * Packets following a pending chunk are held back in the player's {@link PendingPacketQueue} to
 * keep their original order.
 */
@NullMarked
public class ObfuscationListener extends PacketListenerAbstract {

  /**
   * Packets that always have to stay in order with chunk packets.
   */
  private static final Set<PacketTypeCommon> PACKET_TYPES_CHUNK = Set.of(
      PacketType.Play.Server.CHUNK_DATA,
      PacketType.Play.Server.CHUNK_BATCH_BEGIN,
      PacketType.Play.Server.CHUNK_BATCH_END,
      PacketType.Play.Server.UNLOAD_CHUNK,
      PacketType.Play.Server.CHUNK_BIOMES,
      PacketType.Play.Server.UPDATE_LIGHT,
      PacketType.Play.Server.BLOCK_ENTITY_DATA,
      // Proximity hider updates
      PacketType.Play.Server.BLOCK_CHANGE,
      PacketType.Play.Server.MULTI_BLOCK_CHANGE
  );

  /**
   * Packets that only have to stay in order with chunk packets while the player is respawning.
   */
  private static final Set<PacketTypeCommon> PACKET_TYPES_RESPAWN = Set.of(
      PacketType.Play.Server.RESPAWN,
      PacketType.Play.Server.UPDATE_VIEW_DISTANCE,
      PacketType.Play.Server.UPDATE_VIEW_POSITION,
      PacketType.Play.Server.PLAYER_POSITION_AND_LOOK,
      PacketType.Play.Server.SPAWN_POSITION,
      PacketType.Play.Server.SERVER_DIFFICULTY,
      PacketType.Play.Server.SET_EXPERIENCE,
      PacketType.Play.Server.WORLD_BORDER,
      PacketType.Play.Server.TIME_UPDATE,
      PacketType.Play.Server.CHANGE_GAME_STATE,
      PacketType.Play.Server.ENTITY_STATUS,
      PacketType.Play.Server.DECLARE_COMMANDS,
      PacketType.Play.Server.SOUND_EFFECT,
      PacketType.Play.Server.HELD_ITEM_CHANGE,
      PacketType.Play.Server.WINDOW_ITEMS,
      PacketType.Play.Server.WINDOW_PROPERTY,
      PacketType.Play.Server.SET_SLOT,
      PacketType.Play.Server.UPDATE_ATTRIBUTES,
      PacketType.Play.Server.UPDATE_HEALTH,
      PacketType.Play.Server.PLAYER_ABILITIES,
      PacketType.Play.Server.ENTITY_EFFECT,
      PacketType.Play.Server.UPDATE_SIMULATION_DISTANCE,
      PacketType.Play.Server.INITIALIZE_WORLD_BORDER
  );

  private final ObfuscationPipeline pipeline;
  private final InjectorStatistics statistics;
  private final BukkitPlayerAccessorManager playerManager;

  public ObfuscationListener(Orebfuscator orebfuscator) {
    super(PacketListenerPriority.MONITOR);

    this.pipeline = orebfuscator.obfuscationPipeline();
    this.statistics = orebfuscator.statistics().injector;
    this.playerManager = orebfuscator.playerManager();

    PacketEvents.getAPI().getEventManager().registerListener(this);
  }

  public void unregister() {
    PacketEvents.getAPI().getEventManager().unregisterListener(this);
  }

  @Override
  public void onPacketReceive(@NotNull PacketReceiveEvent event) {
    if (event.getPacketType() == PacketType.Play.Client.CHUNK_BATCH_ACK) {
      var packet = new WrapperPlayClientChunkBatchAck(event);
      packet.setDesiredChunksPerTick(10.0F);
      statistics.injectorBatchSize.add(packet.getDesiredChunksPerTick());
    }
  }

  @Override
  public void onPacketSend(@NotNull PacketSendEvent event) {
    if (event.isCancelled()) {
      return;
    }

    PacketTypeCommon type = event.getPacketType();
    boolean isChunkType = PACKET_TYPES_CHUNK.contains(type);
    if (!isChunkType && !PACKET_TYPES_RESPAWN.contains(type)) {
      return;
    }

    Player bukkitPlayer = event.getPlayer();
    BukkitPlayerAccessor player = bukkitPlayer != null ? this.playerManager.tryGet(bukkitPlayer) : null;
    if (player == null) {
      // our player object is created after the join event, nothing to obfuscate before that
      return;
    }

    var timer = statistics.injectorDelaySync.start();
    try {
      if (type == PacketType.Play.Server.RESPAWN) {
        player.startRespawn();
      }

      PendingPacketQueue queue = player.packetQueue(event.getUser());

      if (type == PacketType.Play.Server.CHUNK_DATA && this.onSendLevelChunk(event, player, queue)) {
        return;
      }

      // keep packet order: hold packets back while there are pending packets in front of them,
      // packets only relevant for respawns are only held back while the player is respawning
      if (!queue.isEmpty() && (isChunkType || player.isRespawning())) {
        queue.enqueue(event);
      }
    } finally {
      timer.stop();
    }
  }

  /**
   * @return true if the packet got delayed for obfuscation
   */
  private boolean onSendLevelChunk(PacketSendEvent event, BukkitPlayerAccessor player, PendingPacketQueue queue) {
    BukkitWorldAccessor world = player.world();
    if (player.hasPermission(PermissionRequirements.BYPASS) || !world.config().needsObfuscation()) {
      return false;
    }

    // read the packet exactly once, directly off the live buffer, into plain fields - no
    // PacketEvents object model (Column/BaseChunk/TileEntity) involved
    RawChunkDataPacket rawPacket = new RawChunkDataPacket(PendingPacketQueue.packetBody(event),
        event.getServerVersion());
    var packet = new BukkitChunkPacketAccessor(rawPacket, world);

    // the original packet is held back; the raw copy is resent once obfuscation is done
    event.setCancelled(true);

    // neighboring chunks get requested asynchronously by the pipeline as we aren't on the main
    // thread, errors and timeouts are already handled (and logged) by the pipeline itself
    var future = pipeline.request(world, player, packet, null).toCompletableFuture();

    int packetId = event.getPacketId();
    var timer = statistics.packetDelayChunk.start();
    queue.enqueue(future, () -> {
      timer.stop();
      return rawPacket.write(packetId);
    });

    return true;
  }
}

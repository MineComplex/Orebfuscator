package net.imprex.orebfuscator.proximity;

import dev.imprex.orebfuscator.PermissionRequirements;
import dev.imprex.orebfuscator.config.api.ProximityConfig;
import dev.imprex.orebfuscator.player.OrebfuscatorPlayer;
import net.imprex.orebfuscator.Orebfuscator;
import net.imprex.orebfuscator.iterop.BukkitPlayerAccessor;
import net.imprex.orebfuscator.iterop.BukkitPlayerAccessorManager;
import net.imprex.orebfuscator.iterop.BukkitWorldAccessor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.PacketEvents;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketListenerAbstract;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketSendEvent;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.protocol.packettype.PacketType;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;

@NullMarked
public class ProximityPacketListener extends PacketListenerAbstract {

  private final BukkitPlayerAccessorManager playerManager;

  public ProximityPacketListener(Orebfuscator orebfuscator) {
    this.playerManager = orebfuscator.playerManager();

    PacketEvents.getAPI().getEventManager().registerListener(this);
  }

  public void unregister() {
    PacketEvents.getAPI().getEventManager().unregisterListener(this);
  }

  @Override
  public void onPacketSend(@NotNull PacketSendEvent event) {
    if (event.getPacketType() != PacketType.Play.Server.UNLOAD_CHUNK) {
      return;
    }

    Player bukkitPlayer = event.getPlayer();
    BukkitPlayerAccessor player = bukkitPlayer != null ? this.playerManager.tryGet(bukkitPlayer) : null;
    if (player == null || player.hasPermission(PermissionRequirements.BYPASS)) {
      return;
    }

    BukkitWorldAccessor world = player.world();
    ProximityConfig proximityConfig = world.config().proximity();
    if (proximityConfig == null || !proximityConfig.isEnabled()) {
      return;
    }

    OrebfuscatorPlayer orebfuscatorPlayer = player.orebfuscatorPlayer();
    WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(event);
    orebfuscatorPlayer.removeChunk(world, packet.getChunkX(), packet.getChunkZ());
  }
}

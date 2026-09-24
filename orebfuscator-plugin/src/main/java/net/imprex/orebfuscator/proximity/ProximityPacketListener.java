package net.imprex.orebfuscator.proximity;

import dev.imprex.orebfuscator.config.OrebfuscatorConfig;
import dev.imprex.orebfuscator.config.api.ProximityConfig;
import dev.imprex.orebfuscator.interop.WorldAccessor;
import net.imprex.orebfuscator.Orebfuscator;
import net.imprex.orebfuscator.iterop.BukkitWorldAccessor;
import net.imprex.orebfuscator.player.OrebfuscatorPlayer;
import net.imprex.orebfuscator.player.OrebfuscatorPlayerMap;
import net.imprex.orebfuscator.util.PermissionUtil;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.PacketEvents;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketListenerAbstract;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketSendEvent;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.protocol.packettype.PacketType;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;

public class ProximityPacketListener extends PacketListenerAbstract {

    private final OrebfuscatorConfig config;
    private final OrebfuscatorPlayerMap playerMap;

    public ProximityPacketListener(Orebfuscator orebfuscator) {
        PacketEvents.getAPI().getEventManager().registerListener(this);

        this.config = orebfuscator.getOrebfuscatorConfig();
        this.playerMap = orebfuscator.getPlayerMap();
    }

    public void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.UNLOAD_CHUNK)
            return;

        Player player = event.getPlayer();
        if (PermissionUtil.canBypassObfuscate(player))
            return;

        WorldAccessor worldAccessor = BukkitWorldAccessor.get(player.getWorld());
        ProximityConfig proximityConfig = config.world(worldAccessor).proximity();
        if (proximityConfig == null || !proximityConfig.isEnabled()) {
            return;
        }

        OrebfuscatorPlayer orebfuscatorPlayer = this.playerMap.get(player);
        if (orebfuscatorPlayer != null) {
            WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(event);
            orebfuscatorPlayer.removeChunk(packet.getChunkX(), packet.getChunkZ());
        }
    }

}

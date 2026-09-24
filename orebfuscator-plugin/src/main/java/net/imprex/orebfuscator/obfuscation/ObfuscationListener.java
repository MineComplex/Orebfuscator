package net.imprex.orebfuscator.obfuscation;

import dev.imprex.orebfuscator.config.OrebfuscatorConfig;
import dev.imprex.orebfuscator.config.api.AdvancedConfig;
import dev.imprex.orebfuscator.logging.OfcLogger;
import dev.imprex.orebfuscator.util.BlockPos;
import net.imprex.orebfuscator.Orebfuscator;
import net.imprex.orebfuscator.iterop.BukkitChunkPacketAccessor;
import net.imprex.orebfuscator.iterop.BukkitWorldAccessor;
import net.imprex.orebfuscator.iterop.RawChunkDataPacket;
import net.imprex.orebfuscator.player.OrebfuscatorPlayer;
import net.imprex.orebfuscator.player.OrebfuscatorPlayerMap;
import net.imprex.orebfuscator.util.PermissionUtil;
import net.imprex.orebfuscator.util.RollingAverage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.PacketEvents;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketListenerAbstract;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketListenerPriority;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketReceiveEvent;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketSendEvent;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.protocol.packettype.PacketType;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChunkBatchAck;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Obfuscation happens asynchronously, so the original chunk-data packet is cancelled here and a
 * hand-rolled raw copy ({@link RawChunkDataPacket}) is resent manually once the future
 * completes. PacketEvents has no ProtocolLib-style "delay transmission" mechanism, so cancel +
 * manual resend is the equivalent: without cancelling, the unobfuscated packet would already be
 * on the wire by the time the async obfuscation result comes back.
 */
public class ObfuscationListener extends PacketListenerAbstract {

    private final OrebfuscatorConfig config;
    private final OrebfuscatorPlayerMap playerMap;
    private final ObfuscationSystem obfuscationSystem;

    private final RollingAverage originalSize = new RollingAverage(2048);
    private final RollingAverage obfuscatedSize = new RollingAverage(2048);

    public ObfuscationListener(Orebfuscator orebfuscator) {
        super(PacketListenerPriority.MONITOR);
        this.config = orebfuscator.getOrebfuscatorConfig();
        this.playerMap = orebfuscator.getPlayerMap();
        this.obfuscationSystem = orebfuscator.getObfuscationSystem();

        PacketEvents.getAPI().getEventManager().registerListener(this);

        var statistics = orebfuscator.getStatistics();
        statistics.setOriginalChunkSize(() -> (long) originalSize.average());
        statistics.setObfuscatedChunkSize(() -> (long) obfuscatedSize.average());
    }

    public void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    @Override
    public void onPacketReceive(@NotNull PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.CHUNK_BATCH_ACK) {
            new WrapperPlayClientChunkBatchAck(event).setDesiredChunksPerTick(10.0F);
        }
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (event.isCancelled())
            return;

        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA)
            return;

        Player player = event.getPlayer();
        BukkitWorldAccessor worldAccessor = BukkitWorldAccessor.get(player.getWorld());
        if (this.shouldNotObfuscate(player, worldAccessor)) {
            return;
        }

        // read the packet exactly once, directly off the live buffer, into plain fields - no
        // PacketEvents object model (Column/BaseChunk/TileEntity) involved
        RawChunkDataPacket rawPacket = new RawChunkDataPacket(event.getByteBuf(), event.getServerVersion());
        BukkitChunkPacketAccessor packet = new BukkitChunkPacketAccessor(rawPacket, worldAccessor);

        // the original packet is held back; the raw copy is resent manually below
        event.setCancelled(true);

        CompletableFuture<ObfuscationResult> future = this.obfuscationSystem.obfuscate(packet);

        AdvancedConfig advancedConfig = this.config.advanced();
        if (advancedConfig.hasObfuscationTimeout()) {
            future = future.orTimeout(advancedConfig.obfuscationTimeout(), TimeUnit.MILLISECONDS);
        }

        future.whenComplete((chunk, throwable) -> {
            if (throwable != null) {
                this.completeExceptionally(packet, throwable);
            } else if (chunk != null) {
                this.complete(player, packet, chunk);
            } else {
                OfcLogger.warn(String.format("skipping chunk[world=%s, x=%d, z=%d] because obfuscation result is missing",
                        packet.worldAccessor.getName(), packet.chunkX(), packet.chunkZ()));
            }

            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, rawPacket.write(event.getPacketId()));
        });
    }

    private boolean shouldNotObfuscate(Player player, BukkitWorldAccessor worldAccessor) {
        return PermissionUtil.canBypassObfuscate(player) || !config.world(worldAccessor).needsObfuscation();
    }

    private void completeExceptionally(BukkitChunkPacketAccessor packet, Throwable throwable) {
        if (throwable instanceof TimeoutException) {
            OfcLogger.warn(String.format("Obfuscation for chunk[world=%s, x=%d, z=%d] timed out",
                    packet.worldAccessor.getName(), packet.chunkX(), packet.chunkZ()));
        } else {
            OfcLogger.error(String.format("An error occurred while obfuscating chunk[world=%s, x=%d, z=%d]",
                    packet.worldAccessor.getName(), packet.chunkX(), packet.chunkZ()), throwable);
        }
    }

    private void complete(Player player, BukkitChunkPacketAccessor packet, ObfuscationResult chunk) {
        originalSize.add(packet.data().length);
        obfuscatedSize.add(chunk.getData().length);

        packet.setData(chunk.getData());

        Set<BlockPos> blockEntities = chunk.getBlockEntities();
        if (!blockEntities.isEmpty()) {
            packet.filterBlockEntities(blockEntities::contains);
        }

        OrebfuscatorPlayer orebfuscatorPlayer = this.playerMap.get(player);
        if (orebfuscatorPlayer != null) {
            orebfuscatorPlayer.addChunk(packet.chunkX(), packet.chunkZ(), chunk.getProximityBlocks());
        }
    }
}

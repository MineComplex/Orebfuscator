package net.imprex.orebfuscator.iterop;

import dev.imprex.orebfuscator.interop.ChunkPacketAccessor;
import dev.imprex.orebfuscator.interop.WorldAccessor;
import dev.imprex.orebfuscator.util.BlockPos;

import java.util.BitSet;
import java.util.function.Predicate;

/**
 * Bridges the {@link RawChunkDataPacket} (raw, unparsed wire bytes) to Orebfuscator's core
 * obfuscation engine, which reads/writes the same wire format directly. The obfuscated bytes
 * returned by the core engine are already in the correct on-wire section format, so they're
 * handed straight back to {@link RawChunkDataPacket#setSectionData(byte[])} with no re-parsing.
 */
public class BukkitChunkPacketAccessor implements ChunkPacketAccessor {

    public final BukkitWorldAccessor worldAccessor;

    private final BitSet sectionMask;
    private final RawChunkDataPacket packet;

    public BukkitChunkPacketAccessor(RawChunkDataPacket packet, BukkitWorldAccessor worldAccessor) {
        this.packet = packet;
        this.worldAccessor = worldAccessor;

        this.sectionMask = new BitSet();
        this.sectionMask.set(0, worldAccessor.getSectionCount());
    }

    @Override
    public WorldAccessor world() {
        return this.worldAccessor;
    }

    @Override
    public int chunkX() {
        return this.packet.chunkX();
    }

    @Override
    public int chunkZ() {
        return this.packet.chunkZ();
    }

    @Override
    public boolean isSectionPresent(int index) {
        return this.sectionMask.get(index);
    }

    @Override
    public byte[] data() {
        return this.packet.sectionData();
    }

    @Override
    public void setData(byte[] data) {
        this.packet.setSectionData(data);
    }

    @Override
    public void filterBlockEntities(Predicate<BlockPos> predicate) {
        this.packet.removeTileEntitiesIf(predicate);
    }

    public boolean isEmpty() {
        return this.sectionMask.isEmpty();
    }

}

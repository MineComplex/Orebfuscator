package net.imprex.orebfuscator.iterop;

import dev.imprex.orebfuscator.util.BlockPos;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.manager.server.ServerVersion;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.wrapper.PacketWrapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Minimal 1.18+ clientbound level_chunk_with_light reader/writer. Reads only the fields
 * Orebfuscator actually needs (chunk coords, section data, tile entity positions) as plain
 * fields instead of decoding the whole packet into PacketEvents' Column/BaseChunk/TileEntity
 * object graph. Heightmaps and light data are opaque byte spans that are never interpreted,
 * just copied back out unchanged on write.
 */
public class RawChunkDataPacket {

    private final int chunkX;
    private final int chunkZ;

    private final byte[] heightmapBytes;
    private byte[] sectionData;
    private final List<TileEntityEntry> tileEntities;
    private final byte[] lightData;

    public RawChunkDataPacket(Object buffer, ServerVersion serverVersion) {
        this.chunkX = ByteBufHelper.readInt(buffer);
        this.chunkZ = ByteBufHelper.readInt(buffer);

        this.heightmapBytes = readHeightmapBytes(buffer, serverVersion);

        int sectionLength = ByteBufHelper.readVarInt(buffer);
        this.sectionData = new byte[sectionLength];
        ByteBufHelper.readBytes(buffer, this.sectionData);

        int tileEntityCount = ByteBufHelper.readVarInt(buffer);
        this.tileEntities = new ArrayList<>(tileEntityCount);
        for (int i = 0; i < tileEntityCount; i++) {
            this.tileEntities.add(TileEntityEntry.read(buffer));
        }

        // light data is always the last field in the packet, so whatever remains needs no parsing
        this.lightData = new byte[ByteBufHelper.readableBytes(buffer)];
        ByteBufHelper.readBytes(buffer, this.lightData);
    }

    private static byte[] readHeightmapBytes(Object buffer, ServerVersion serverVersion) {
        int start = ByteBufHelper.readerIndex(buffer);

        if (serverVersion.isNewerThanOrEquals(ServerVersion.V_1_21_5)) {
            // Map<HeightmapType, long[]>: varint count, then per entry a varint enum ordinal
            // followed by a varint long-count and that many longs - skip without allocating.
            int mapSize = ByteBufHelper.readVarInt(buffer);
            for (int i = 0; i < mapSize; i++) {
                ByteBufHelper.readVarInt(buffer); // heightmap type ordinal
                int longCount = ByteBufHelper.readVarInt(buffer);
                ByteBufHelper.skipBytes(buffer, longCount * Long.BYTES);
            }
        } else {
            // NBT has no outer length prefix, only the codec knows how to walk it - parse and
            // discard purely to advance the reader to the right position.
            PacketWrapper.createUniversalPacketWrapper(buffer).readNBT();
        }

        int end = ByteBufHelper.readerIndex(buffer);
        byte[] bytes = new byte[end - start];
        ByteBufHelper.getBytes(buffer, start, bytes);
        return bytes;
    }

    public int chunkX() {
        return this.chunkX;
    }

    public int chunkZ() {
        return this.chunkZ;
    }

    public byte[] sectionData() {
        return this.sectionData;
    }

    public void setSectionData(byte[] sectionData) {
        this.sectionData = sectionData;
    }

    public void removeTileEntitiesIf(Predicate<BlockPos> predicate) {
        Iterator<TileEntityEntry> iterator = this.tileEntities.iterator();
        while (iterator.hasNext()) {
            TileEntityEntry entry = iterator.next();
            BlockPos absolute = new BlockPos(
                    (this.chunkX << 4) | entry.localX, entry.y, (this.chunkZ << 4) | entry.localZ);
            if (predicate.test(absolute)) {
                iterator.remove();
            }
        }
    }

    /**
     * Builds the fully framed packet buffer (packet id + body), ready to hand straight to
     * {@code PlayerManager#sendPacketSilently(Object, Object)}.
     */
    public Object write(int packetId) {
        Object buffer = UnpooledByteBufAllocationHelper.buffer();

        ByteBufHelper.writeVarInt(buffer, packetId);
        ByteBufHelper.writeInt(buffer, this.chunkX);
        ByteBufHelper.writeInt(buffer, this.chunkZ);
        ByteBufHelper.writeBytes(buffer, this.heightmapBytes);

        ByteBufHelper.writeVarInt(buffer, this.sectionData.length);
        ByteBufHelper.writeBytes(buffer, this.sectionData);

        ByteBufHelper.writeVarInt(buffer, this.tileEntities.size());
        for (TileEntityEntry entry : this.tileEntities) {
            ByteBufHelper.writeBytes(buffer, entry.raw);
        }

        ByteBufHelper.writeBytes(buffer, this.lightData);

        return buffer;
    }

    private record TileEntityEntry(int localX, int localZ, int y, byte[] raw) {

        static TileEntityEntry read(Object buffer) {
                int start = ByteBufHelper.readerIndex(buffer);

                int packedByte = ByteBufHelper.readByte(buffer);
                int localX = (packedByte & 0xF0) >> 4;
                int localZ = packedByte & 0x0F;
                int y = ByteBufHelper.readShort(buffer);
                ByteBufHelper.readVarInt(buffer); // block entity type, kept verbatim below, not needed here

                // same NBT-skip situation as the heightmaps: parse and discard just to find the end.
                PacketWrapper.createUniversalPacketWrapper(buffer).readNBT();

                int end = ByteBufHelper.readerIndex(buffer);
                byte[] raw = new byte[end - start];
                ByteBufHelper.getBytes(buffer, start, raw);

                return new TileEntityEntry(localX, localZ, y, raw);
            }
        }
}

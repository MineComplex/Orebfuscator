package net.imprex.orebfuscator.iterop;

import dev.imprex.orebfuscator.interop.ChunkPacketAccessor;
import dev.imprex.orebfuscator.obfuscation.ObfuscationResponse;
import java.util.BitSet;
import org.jspecify.annotations.NullMarked;

/**
 * Bridges the {@link RawChunkDataPacket} (raw, unparsed wire bytes) to Orebfuscator's core
 * obfuscation engine, which reads/writes the same wire format directly. The obfuscated bytes
 * returned by the core engine are already in the correct on-wire section format, so they're
 * handed straight back to {@link RawChunkDataPacket#setSectionData(byte[])} with no re-parsing.
 */
@NullMarked
public class BukkitChunkPacketAccessor implements ChunkPacketAccessor {

  private final BitSet sectionMask;
  private final RawChunkDataPacket packet;

  public BukkitChunkPacketAccessor(RawChunkDataPacket packet, BukkitWorldAccessor worldAccessor) {
    this.packet = packet;

    this.sectionMask = new BitSet();
    this.sectionMask.set(0, worldAccessor.sectionCount());
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
  public void update(ObfuscationResponse response) {
    this.packet.setSectionData(response.data());

    // RawChunkDataPacket already resolves block entity positions to absolute world coordinates
    if (!response.blockEntities().isEmpty()) {
      this.packet.removeTileEntitiesIf(response.blockEntities()::contains);
    }
  }

  public boolean isEmpty() {
    return this.sectionMask.isEmpty();
  }
}

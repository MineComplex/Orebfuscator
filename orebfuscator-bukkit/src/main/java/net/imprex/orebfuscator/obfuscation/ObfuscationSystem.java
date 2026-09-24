package net.imprex.orebfuscator.obfuscation;

import dev.imprex.orebfuscator.obfuscation.DeobfuscationWorker;
import dev.imprex.orebfuscator.util.BlockPos;
import java.util.Collection;
import net.imprex.orebfuscator.Orebfuscator;
import net.imprex.orebfuscator.iterop.BukkitWorldAccessorManager;
import org.bukkit.block.Block;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ObfuscationSystem {

  private final Orebfuscator orebfuscator;
  private final BukkitWorldAccessorManager worldManager;
  private final DeobfuscationWorker deobfuscationWorker;

  private @Nullable ObfuscationListener obfuscationListener;

  public ObfuscationSystem(Orebfuscator orebfuscator) {
    this.orebfuscator = orebfuscator;
    this.worldManager = orebfuscator.worldManager();

    this.deobfuscationWorker = new DeobfuscationWorker(orebfuscator);
    DeobfuscationListener.createAndRegister(orebfuscator, this);
  }

  public void registerChunkListener() {
    this.obfuscationListener = new ObfuscationListener(orebfuscator);
  }

  public void deobfuscate(Block block) {
    var world = this.worldManager.get(block.getWorld());
    var blockPos = new BlockPos(block.getX(), block.getY(), block.getZ());
    this.deobfuscationWorker.deobfuscate(world, blockPos);
  }

  public void deobfuscate(Collection<? extends Block> blocks) {
    if (blocks.isEmpty()) {
      return;
    }

    var world = this.worldManager.get(blocks.stream().findFirst().get().getWorld());
    var blockPos = blocks.stream()
        .map(block -> new BlockPos(block.getX(), block.getY(), block.getZ()))
        .toList();

    this.deobfuscationWorker.deobfuscate(world, blockPos);
  }

  public void shutdown() {
    if (this.obfuscationListener != null) {
      this.obfuscationListener.unregister();
    }
  }
}

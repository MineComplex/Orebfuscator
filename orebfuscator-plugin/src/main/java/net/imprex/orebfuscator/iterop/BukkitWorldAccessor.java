package net.imprex.orebfuscator.iterop;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import dev.imprex.orebfuscator.interop.WorldAccessor;
import dev.imprex.orebfuscator.logging.OfcLogger;

public class BukkitWorldAccessor implements WorldAccessor {

  private static final Map<World, BukkitWorldAccessor> ACCESSOR_LOOKUP = new ConcurrentHashMap<>();

  public static BukkitWorldAccessor get(World world) {
    return ACCESSOR_LOOKUP.computeIfAbsent(world, key -> {
      OfcLogger.warn("Created world accessor outside of event!");
      return new BukkitWorldAccessor(key);
    });
  }

  private static int blockToSectionCoord(int block) {
    return block >> 4;
  }

  public static Collection<BukkitWorldAccessor> getWorlds() {
    return ACCESSOR_LOOKUP.values();
  }

  public static void registerListener(Plugin plugin) {
    for (World world : Bukkit.getWorlds()) {
      ACCESSOR_LOOKUP.put(world, new BukkitWorldAccessor(world));
    }

    Bukkit.getPluginManager().registerEvents(new Listener() {
      @EventHandler
      public void onWorldUnload(WorldLoadEvent event) {
        World world = event.getWorld();
        ACCESSOR_LOOKUP.put(world, new BukkitWorldAccessor(world));
      }

      @EventHandler
      public void onWorldUnload(WorldUnloadEvent event) {
        ACCESSOR_LOOKUP.remove(event.getWorld());
      }
    }, plugin);
  }

  public final World world;

  private final int maxHeight;
  private final int minHeight;

  private BukkitWorldAccessor(World world) {
    this.world = Objects.requireNonNull(world);
    this.maxHeight = world.getMaxHeight();
    this.minHeight = world.getMinHeight();
  }

  @Override
  public String getName() {
    return this.world.getName();
  }

  @Override
  public int getHeight() {
    return this.maxHeight - this.minHeight;
  }

  @Override
  public int getMinBuildHeight() {
    return this.minHeight;
  }

  @Override
  public int getMaxBuildHeight() {
    return this.maxHeight;
  }

  @Override
  public int getSectionCount() {
    return this.getMaxSection() - this.getMinSection();
  }

  @Override
  public int getMinSection() {
    return blockToSectionCoord(this.getMinBuildHeight());
  }

  @Override
  public int getMaxSection() {
    return blockToSectionCoord(this.getMaxBuildHeight() - 1) + 1;
  }

  @Override
  public int getSectionIndex(int y) {
    return blockToSectionCoord(y) - getMinSection();
  }

  @Override
  public int hashCode() {
    return this.world.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    return obj instanceof BukkitWorldAccessor other && this.world.equals(other.world);
  }

  @Override
  public String toString() {
    return String.format("[%s, minY=%s, maxY=%s]", world.getName(), minHeight, maxHeight);
  }
}

package net.imprex.orebfuscator.obfuscation;

import dev.imprex.orebfuscator.logging.OfcLogger;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.event.PacketSendEvent;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.protocol.player.User;
import ru.minecomplex.network.shared.com.github.retrooper.packetevents.wrapper.PacketWrapper;

/**
 * PacketEvents has no ProtocolLib-style "delay transmission" mechanism, so delayed packets are
 * cancelled and resent manually. To keep the original packet order (e.g. unload/light/block
 * updates must never overtake the chunk they belong to) every packet that follows a pending
 * chunk gets queued as well and is flushed strictly in order once all packets in front of it
 * are done.
 * <p>
 * Flushing always happens on the channel's event loop, the same thread packet listeners run
 * on, so a packet that isn't queued can never overtake a queued one.
 */
@NullMarked
public class PendingPacketQueue {

  private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);

  /**
   * Returns a buffer positioned at the start of the packet body (after the packet id). If another
   * listener already read the packet through a wrapper the event's buffer is consumed (and may be
   * modified), in which case the wrapper gets re-encoded into a fresh buffer.
   */
  public static Object packetBody(PacketSendEvent event) {
    PacketWrapper<?> wrapper = event.getLastUsedWrapper();
    if (wrapper == null) {
      return event.getByteBuf();
    }

    Object previousBuffer = wrapper.getBuffer();
    Object buffer = UnpooledByteBufAllocationHelper.buffer();
    try {
      wrapper.setBuffer(buffer);
      wrapper.write();
    } finally {
      wrapper.setBuffer(previousBuffer);
    }
    return buffer;
  }

  private final User user;
  private final ArrayDeque<Entry> queue = new ArrayDeque<>();

  public PendingPacketQueue(User user) {
    this.user = user;
  }

  public synchronized boolean isEmpty() {
    return this.queue.isEmpty();
  }

  /**
   * Cancels the event and queues a verbatim copy of the packet.
   */
  public void enqueue(PacketSendEvent event) {
    Object buffer = packetBody(event);
    int packetId = event.getPacketId();

    byte[] body = new byte[ByteBufHelper.readableBytes(buffer)];
    ByteBufHelper.getBytes(buffer, ByteBufHelper.readerIndex(buffer), body);

    event.setCancelled(true);

    this.enqueue(COMPLETED, () -> {
      Object copy = UnpooledByteBufAllocationHelper.buffer(body.length + 5);
      ByteBufHelper.writeVarInt(copy, packetId);
      ByteBufHelper.writeBytes(copy, body);
      return copy;
    });
  }

  /**
   * Queues a packet that will be built and sent once the given future and every packet in
   * front of it are done. The event has to be cancelled by the caller.
   */
  public void enqueue(CompletableFuture<?> future, Supplier<Object> packet) {
    synchronized (this) {
      this.queue.add(new Entry(future, packet));
    }

    future.whenComplete((v, throwable) -> ChannelHelper.runInEventLoop(this.user.getChannel(), this::flush));
  }

  private synchronized void flush() {
    Entry entry;
    while ((entry = this.queue.peek()) != null && entry.future.isDone()) {
      this.queue.poll();

      try {
        this.user.sendPacketSilently(entry.packet.get());
      } catch (Exception e) {
        OfcLogger.error("An error occurred while sending a delayed packet", e);
      }
    }
  }

  private record Entry(CompletableFuture<?> future, Supplier<Object> packet) {
  }
}

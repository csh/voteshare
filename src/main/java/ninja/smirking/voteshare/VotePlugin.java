package ninja.smirking.voteshare;

import com.google.common.base.Preconditions;
import com.google.common.collect.Queues;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.logging.Level;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;

/**
 * @author Connor Spencer Harries
 */
public final class VotePlugin extends JavaPlugin {
  public static final byte[] CHANNEL_NAME = ("voteshare").getBytes(StandardCharsets.UTF_8);

  private final Queue<Vote> queue;
  private BinaryJedisPubSub pubSub;
  private ListenerType type;
  private JedisPool pool;

  public VotePlugin() {
    this.queue = Queues.newConcurrentLinkedQueue();
  }

  @Override
  public void onEnable() {
    saveDefaultConfig();

    try {
      type = ListenerType.valueOf(getConfig().getString("mode", ListenerType.RECEIVER.name()));
    } catch (IllegalArgumentException ex) {
      type = ListenerType.RECEIVER;
    }

    JedisPoolConfig config = new JedisPoolConfig();
    String host = getConfig().getString("redis.host", "127.0.0.1");
    String auth = getConfig().getString("redis.auth", "");
    int port = getConfig().getInt("redis.port", 6379);

    getLogger().log(Level.INFO, "Voteshare is running in {0} mode!", type.name());

    if (auth.isEmpty()) {
      pool = new JedisPool(config, host, port, 0);
    } else {
      pool = new JedisPool(config, host, port, 0, auth);
    }

    if (type == ListenerType.BROADCAST) {
      long pollInterval = getConfig().getLong("poll-interval", 100L);

      if (pollInterval <= 60L && !getConfig().getBoolean("allow-unsafe-interval", false)) {
        getLogger().log(Level.WARNING, "poll-interval has automatically been set to 60L as the set value ({0}L) is rather low", String.valueOf(pollInterval));
        getLogger().log(Level.WARNING, "to disable this behaviour set allow-unsafe-interval to true in the config");
        pollInterval = 60L;
      }

      getServer().getPluginManager().registerEvents(new Listener() {
        @EventHandler
        public void onDisable(PluginDisableEvent event) {
          if (event.getPlugin().getName().equalsIgnoreCase("Votifier")) {
            getLogger().log(Level.INFO, "Votifier was disabled, disabling self!");
            getPluginLoader().disablePlugin(VotePlugin.this);
          }
        }

        @EventHandler
        public void onVote(VotifierEvent event) {
          queue.offer(event.getVote());
        }
      }, this);

      getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
        if (queue.size() < 1) {
          return;
        }
        try (Jedis jedis = pool.getResource()) {
          Transaction transaction = jedis.multi();
          while(true) {
            Vote vote = queue.poll();
            if (vote == null) {
              break;
            }
            transaction.publish(CHANNEL_NAME, serialize(vote));
          }
          transaction.exec();
        } catch (JedisException ex) {
          getLogger().log(Level.SEVERE, "Error processing pending votes: ", ex);
        }
      }, pollInterval, pollInterval);
    } else {
      pubSub = new VoteSubscriber(this);
      getServer().getScheduler().runTaskAsynchronously(this, () -> {
        try (Jedis jedis = pool.getResource()) {
          jedis.subscribe(pubSub, CHANNEL_NAME);
        } catch (JedisException ex) {
          getLogger().log(Level.SEVERE, "Failed to subscribe to channel: ", ex);
        }
      });
    }
  }

  @Override
  public void onDisable() {
    queue.clear();
    if (pool != null) {
      if (type == ListenerType.BROADCAST) {
        getLogger().log(Level.INFO, "Attempting to unsubscribe our pubsub!");
        try {
          pubSub.unsubscribe(CHANNEL_NAME);
        } catch (JedisException ex) {
          getLogger().log(Level.SEVERE, "Failed to unsubscribe from channel: ", ex);
        }
      } else {
        HandlerList.unregisterAll(this);
      }
      getLogger().log(Level.INFO, "Destroying jedis pool");
      pool.destroy();
      pool = null;
    }
  }

  private static byte[] serialize(Vote vote) {
    Preconditions.checkNotNull(vote, "vote should not be null!");
    ByteArrayDataOutput output = ByteStreams.newDataOutput();
    output.writeUTF(vote.getServiceName());
    output.writeUTF(vote.getTimeStamp());
    output.writeUTF(vote.getUsername());
    output.writeUTF(vote.getAddress());
    return output.toByteArray();
  }

  private enum ListenerType {
    BROADCAST,
    RECEIVER
  }
}

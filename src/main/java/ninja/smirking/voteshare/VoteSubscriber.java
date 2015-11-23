package ninja.smirking.voteshare;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

import java.util.logging.Level;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.scheduler.BukkitRunnable;
import redis.clients.jedis.BinaryJedisPubSub;

/**
 * @author Connor Spencer Harries
 */
public class VoteSubscriber extends BinaryJedisPubSub {
  private final VotePlugin plugin;

  public VoteSubscriber(VotePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void onMessage(byte[] channel, byte[] message) {
    ByteArrayDataInput input = ByteStreams.newDataInput(message);
    Vote vote = new Vote();
    vote.setServiceName(input.readUTF());
    vote.setTimeStamp(input.readUTF());
    vote.setUsername(input.readUTF());
    vote.setAddress(input.readUTF());

    new BukkitRunnable() {
      @Override
      public void run() {
        VotifierEvent event = new VotifierEvent(vote);
        plugin.getServer().getPluginManager().callEvent(event);
      }
    }.runTask(plugin);
  }

  @Override
  public void onPMessage(byte[] pattern, byte[] channel, byte[] message) {

  }

  @Override
  public void onSubscribe(byte[] channel, int subscribedChannels) {
    plugin.getLogger().log(Level.INFO, "Listening for vote events!");
  }

  @Override
  public void onUnsubscribe(byte[] channel, int subscribedChannels) {

  }

  @Override
  public void onPUnsubscribe(byte[] pattern, int subscribedChannels) {

  }

  @Override
  public void onPSubscribe(byte[] pattern, int subscribedChannels) {

  }
}

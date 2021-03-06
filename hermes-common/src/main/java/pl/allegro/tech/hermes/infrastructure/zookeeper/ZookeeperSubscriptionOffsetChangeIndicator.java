package pl.allegro.tech.hermes.infrastructure.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.EnsurePath;
import pl.allegro.tech.hermes.api.TopicName;
import pl.allegro.tech.hermes.common.exception.InternalProcessingException;
import pl.allegro.tech.hermes.common.kafka.KafkaTopicName;
import pl.allegro.tech.hermes.common.kafka.offset.PartitionOffset;
import pl.allegro.tech.hermes.common.kafka.offset.PartitionOffsets;
import pl.allegro.tech.hermes.common.kafka.offset.SubscriptionOffsetChangeIndicator;
import pl.allegro.tech.hermes.domain.subscription.SubscriptionRepository;

import java.util.List;

import static com.google.common.base.Charsets.UTF_8;

public class ZookeeperSubscriptionOffsetChangeIndicator implements SubscriptionOffsetChangeIndicator {

    private final CuratorFramework zookeeper;

    private final ZookeeperPaths paths;

    private final SubscriptionRepository subscriptionRepository;

    public ZookeeperSubscriptionOffsetChangeIndicator(
            CuratorFramework zookeeper, ZookeeperPaths paths, SubscriptionRepository repository) {

        this.zookeeper = zookeeper;
        this.paths = paths;
        this.subscriptionRepository = repository;
    }

    @Override
    public void setSubscriptionOffset(TopicName topicName, String subscriptionName, String brokersClusterName, PartitionOffset partitionOffset) {
        subscriptionRepository.ensureSubscriptionExists(topicName, subscriptionName);

        String offsetPath = paths.offsetPath(topicName, subscriptionName, partitionOffset.getTopic(), brokersClusterName, partitionOffset.getPartition());
        try {
            new EnsurePath(offsetPath).ensure(zookeeper.getZookeeperClient());
            zookeeper.setData().forPath(offsetPath, String.valueOf(partitionOffset.getOffset()).getBytes(UTF_8));
        } catch (Exception e) {
            throw new InternalProcessingException(e);
        }
    }

    @Override
    public PartitionOffsets getSubscriptionOffsets(TopicName topic, String subscriptionName, String brokersClusterName) {
        subscriptionRepository.ensureSubscriptionExists(topic, subscriptionName);
        String kafkaTopicsPath = paths.subscribedKafkaTopicsPath(topic, subscriptionName);

        PartitionOffsets allOffsets = new PartitionOffsets();
        getZookeeperChildrenForPath(kafkaTopicsPath).stream().map(KafkaTopicName::valueOf).forEach(kafkaTopic ->
                        allOffsets.addAll(getOffsetsForKafkaTopic(topic, kafkaTopic, subscriptionName, brokersClusterName))
        );
        return allOffsets;
    }

    private PartitionOffsets getOffsetsForKafkaTopic(TopicName topic, KafkaTopicName kafkaTopicName, String subscriptionName, String brokersClusterName) {
        String offsetsPath = paths.offsetsPath(topic, subscriptionName, kafkaTopicName, brokersClusterName);

        PartitionOffsets offsets = new PartitionOffsets();
        for (String partitionAsString : getZookeeperChildrenForPath(offsetsPath)) {
            Integer partition = Integer.valueOf(partitionAsString);
            offsets.add(new PartitionOffset(
                    kafkaTopicName,
                    getOffsetForPartition(topic, kafkaTopicName, subscriptionName, brokersClusterName, partition),
                    partition
            ));
        }
        return offsets;
    }

    private List<String> getZookeeperChildrenForPath(String path) {
        try {
            return zookeeper.getChildren().forPath(path);
        } catch (Exception e) {
            throw new InternalProcessingException(e);
        }
    }

    private Long getOffsetForPartition(TopicName topic, KafkaTopicName kafkaTopicName, String subscriptionName, String brokersClusterName, int partitionId) {
        try {
            return Long.valueOf(new String(
                    zookeeper.getData().forPath(paths.offsetPath(topic, subscriptionName, kafkaTopicName, brokersClusterName, partitionId)),
                    UTF_8));
        } catch (Exception e) {
            throw new InternalProcessingException(e);
        }
    }

}

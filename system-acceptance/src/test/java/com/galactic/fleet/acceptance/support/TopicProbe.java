package com.galactic.fleet.acceptance.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Listens to one topic from "now" onwards. Open the probe BEFORE triggering the behaviour, then ask
 * it for the record you expect — anything already on the topic (previous tests, background traffic)
 * is ignored, and unrelated records arriving meanwhile are skipped by the predicate.
 */
public final class TopicProbe implements AutoCloseable {

    private final String topic;
    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    private TopicProbe(String topic, KafkaConsumer<String, String> consumer) {
        this.topic = topic;
        this.consumer = consumer;
    }

    public static TopicProbe atEndOf(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Fleet.KAFKA,
                ConsumerConfig.GROUP_ID_CONFIG, "system-acceptance-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));

        // Output topics are created on first publish; asking for metadata creates them if needed.
        List<PartitionInfo> partitions = List.of();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (partitions.isEmpty() && System.nanoTime() < deadline) {
            List<PartitionInfo> found = consumer.partitionsFor(topic, Duration.ofSeconds(5));
            partitions = found == null ? List.of() : found;
        }
        if (partitions.isEmpty()) {
            consumer.close();
            throw new AssertionError("Topic '" + topic + "' does not exist on " + Fleet.KAFKA);
        }

        List<TopicPartition> assignment = partitions.stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .toList();
        consumer.assign(assignment);
        consumer.seekToEnd(assignment);
        assignment.forEach(consumer::position); // seekToEnd is lazy — pin the offsets now
        return new TopicProbe(topic, consumer);
    }

    /** Waits for the first record matching the predicate, or fails with what was seen instead. */
    public ConsumerRecord<String, String> await(Predicate<ConsumerRecord<String, String>> expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                seen.add(record);
                if (expected.test(record)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No matching record on '" + topic + "' within " + timeout + " (" + seen.size()
                + " other record(s) arrived)");
    }

    /** Listens for the whole duration and returns every matching record — use it to prove absence. */
    public List<ConsumerRecord<String, String>> collect(Predicate<ConsumerRecord<String, String>> wanted, Duration duration) {
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                seen.add(record);
                if (wanted.test(record)) {
                    matches.add(record);
                }
            }
        }
        return matches;
    }

    /** Publishes one JSON message, the way an external producer (a ship's sensor gateway) would. */
    public static void publish(String topic, String key, String jsonValue) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Fleet.KAFKA,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(topic, key, jsonValue)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while publishing to " + topic, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("Could not publish to '" + topic + "' on " + Fleet.KAFKA + ": " + e.getCause(), e);
        }
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(2));
    }
}

/*
 * Copyright 2020 The Knative Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.knative.eventing.kafka.broker.dispatcher.http;

import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;

import dev.knative.eventing.kafka.broker.contract.DataPlaneContract;
import dev.knative.eventing.kafka.broker.contract.DataPlaneContract.EgressConfig;
import dev.knative.eventing.kafka.broker.core.filter.Filter;
import dev.knative.eventing.kafka.broker.core.filter.impl.AttributesFilter;
import dev.knative.eventing.kafka.broker.dispatcher.ConsumerRecordHandler;
import dev.knative.eventing.kafka.broker.dispatcher.ConsumerRecordOffsetStrategyFactory;
import dev.knative.eventing.kafka.broker.dispatcher.ConsumerRecordSender;
import dev.knative.eventing.kafka.broker.dispatcher.ConsumerVerticle;
import dev.knative.eventing.kafka.broker.dispatcher.ConsumerVerticleFactory;
import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.CloudEventDeserializer;
import io.cloudevents.kafka.CloudEventSerializer;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

public class HttpConsumerVerticleFactory implements ConsumerVerticleFactory {

  private final static ConsumerRecordSender<String, CloudEvent, HttpResponse<Buffer>> NO_DLQ_SENDER =
    record -> Future.failedFuture("no DLQ set");

  private final Properties consumerConfigs;
  private final WebClient client;
  private final Vertx vertx;
  private final Properties producerConfigs;
  private final ConsumerRecordOffsetStrategyFactory<String, CloudEvent>
    consumerRecordOffsetStrategyFactory;

  /**
   * All args constructor.
   *
   * @param consumerRecordOffsetStrategyFactory consumer offset handling strategy
   * @param consumerConfigs                     base consumer configurations.
   * @param client                              http client.
   * @param vertx                               vertx instance.
   * @param producerConfigs                     base producer configurations.
   */
  public HttpConsumerVerticleFactory(
    final ConsumerRecordOffsetStrategyFactory<String, CloudEvent>
      consumerRecordOffsetStrategyFactory,
    final Properties consumerConfigs,
    final WebClient client,
    final Vertx vertx,
    final Properties producerConfigs) {

    Objects.requireNonNull(consumerRecordOffsetStrategyFactory,
      "provide consumerRecordOffsetStrategyFactory");
    Objects.requireNonNull(consumerConfigs, "provide consumerConfigs");
    Objects.requireNonNull(client, "provide message");
    Objects.requireNonNull(vertx, "provide vertx");
    Objects.requireNonNull(producerConfigs, "provide producerConfigs");

    this.consumerRecordOffsetStrategyFactory = consumerRecordOffsetStrategyFactory;
    this.consumerConfigs = consumerConfigs;
    this.producerConfigs = producerConfigs;
    this.client = client;
    this.vertx = vertx;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public AbstractVerticle get(final DataPlaneContract.Resource resource,
                              final DataPlaneContract.Egress egress) {
    Objects.requireNonNull(resource, "provide resource");
    Objects.requireNonNull(egress, "provide egress");

    final io.vertx.kafka.client.consumer.KafkaConsumer<String, CloudEvent> consumer
      = createConsumer(vertx, resource, egress);

    final io.vertx.kafka.client.producer.KafkaProducer<String, CloudEvent> producer
      = createProducer(vertx, resource, egress);

    final CircuitBreakerOptions circuitBreakerOptions = createCircuitBreakerOptions(resource);

    final var egressConfig = resource.getEgressConfig();

    final var egressDestinationSender = createSender(
      egress.getDestination(),
      circuitBreakerOptions,
      egressConfig
    );
    final var egressDeadLetterSender =
      egressConfig == null || egressConfig.getDeadLetter() == null || egressConfig.getDeadLetter().isEmpty()
        ? NO_DLQ_SENDER
        : createSender(egressConfig.getDeadLetter(), circuitBreakerOptions, egressConfig);

    final var consumerOffsetManager = consumerRecordOffsetStrategyFactory
      .get(consumer, resource, egress);

    final var sinkResponseHandler = new HttpSinkResponseHandler(resource.getTopics(0), producer);

    final var consumerRecordHandler = new ConsumerRecordHandler<>(
      egressDestinationSender,
      (egress.hasFilter()) ? new AttributesFilter(egress.getFilter().getAttributesMap()) : Filter.noop(),
      consumerOffsetManager,
      sinkResponseHandler,
      egressDeadLetterSender
    );

    return new ConsumerVerticle<>(consumer, new HashSet<>(resource.getTopicsList()), consumerRecordHandler);
  }

  private static CircuitBreakerOptions createCircuitBreakerOptions(final DataPlaneContract.Resource resource) {
    if (resource.hasEgressConfig()) {
      return new CircuitBreakerOptions()
        .setMaxRetries(resource.getEgressConfig().getRetry());
    }

    return new CircuitBreakerOptions();
  }

  protected io.vertx.kafka.client.producer.KafkaProducer<String, CloudEvent> createProducer(
    final Vertx vertx,
    final DataPlaneContract.Resource resource,
    final DataPlaneContract.Egress egress) {

    // producerConfigs is a shared object and it acts as a prototype for each consumer instance.
    final var producerConfigs = (Properties) this.producerConfigs.clone();
    producerConfigs.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, resource.getBootstrapServers());

    final var kafkaProducer = new KafkaProducer<>(
      producerConfigs,
      new StringSerializer(),
      new CloudEventSerializer()
    );

    return io.vertx.kafka.client.producer.KafkaProducer.create(vertx, kafkaProducer);
  }

  protected io.vertx.kafka.client.consumer.KafkaConsumer<String, CloudEvent> createConsumer(
    final Vertx vertx,
    final DataPlaneContract.Resource resource,
    final DataPlaneContract.Egress egress) {

    // consumerConfigs is a shared object and it acts as a prototype for each consumer instance.
    final var consumerConfigs = (Properties) this.consumerConfigs.clone();
    consumerConfigs.setProperty(GROUP_ID_CONFIG, egress.getConsumerGroup());
    consumerConfigs.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resource.getBootstrapServers());

    // Note: KafkaConsumer instances are not thread-safe.
    // There are methods thread-safe, but in general they're not.
    final var kafkaConsumer = new KafkaConsumer<>(
      consumerConfigs,
      new StringDeserializer(),
      new CloudEventDeserializer()
    );

    return io.vertx.kafka.client.consumer.KafkaConsumer.create(vertx, kafkaConsumer);
  }

  private HttpConsumerRecordSender createSender(
    final String target,
    final CircuitBreakerOptions circuitBreakerOptions,
    final EgressConfig egress) {

    final var circuitBreaker = CircuitBreaker.create(target, vertx, circuitBreakerOptions);
    circuitBreaker.retryPolicy(computeRetryPolicy(egress));

    return new HttpConsumerRecordSender(
      client,
      target,
      circuitBreaker
    );
  }

  /* package visibility for test */
  static Function<Integer, Long> computeRetryPolicy(final EgressConfig egress) {
    if (egress != null && egress.getBackoffPolicy() != null && egress.getBackoffDelay() > 0) {
      final var delay = egress.getBackoffDelay();
      return switch (egress.getBackoffPolicy()) {
        case Linear -> retryCount -> linearRetryPolicy(retryCount, delay);
        case Exponential, UNRECOGNIZED -> retryCount -> exponentialRetryPolicy(retryCount, delay);
      };
    }
    return retry -> 0L; // Default Vert.x retry policy, it means don't retry
  }

  private static Long exponentialRetryPolicy(final int retryCount, final long delay) {
    return delay * Math.round(Math.pow(2, retryCount));
  }

  private static Long linearRetryPolicy(final int retryCount, final long delay) {
    return delay * retryCount;
  }
}

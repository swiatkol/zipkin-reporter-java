/*
 * Copyright 2016-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.reporter.kafka;

import com.github.charithe.kafka.EphemeralKafkaBroker;
import com.github.charithe.kafka.KafkaJunitRule;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.management.ObjectName;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import zipkin2.Call;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.codec.Encoding;
import zipkin2.codec.SpanBytesDecoder;
import zipkin2.codec.SpanBytesEncoder;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Sender;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zipkin2.TestObjects.CLIENT_SPAN;

public class ITKafkaSender {
  EphemeralKafkaBroker broker = EphemeralKafkaBroker.create();
  @Rule public KafkaJunitRule kafka = new KafkaJunitRule(broker).waitForStartup();

  KafkaSender sender;

  @Before public void open() {
    sender = KafkaSender.create(broker.getBrokerList().get());
  }

  @After public void close() {
    sender.close();
  }

  @Test
  public void sendsSpans() throws Exception {
    send(CLIENT_SPAN, CLIENT_SPAN).execute();

    assertThat(SpanBytesDecoder.JSON_V2.decodeList(readMessage()))
      .containsExactly(CLIENT_SPAN, CLIENT_SPAN);
  }

  @Test
  public void sendsSpans_PROTO3() throws Exception {
    sender.close();
    sender = sender.toBuilder().encoding(Encoding.PROTO3).build();

    send(CLIENT_SPAN, CLIENT_SPAN).execute();

    assertThat(SpanBytesDecoder.PROTO3.decodeList(readMessage()))
      .containsExactly(CLIENT_SPAN, CLIENT_SPAN);
  }

  @Test
  public void sendsSpans_THRIFT() throws Exception {
    sender.close();
    sender = sender.toBuilder().encoding(Encoding.THRIFT).build();

    send(CLIENT_SPAN, CLIENT_SPAN).execute();

    assertThat(SpanBytesDecoder.THRIFT.decodeList(readMessage()))
      .containsExactly(CLIENT_SPAN, CLIENT_SPAN);
  }

  @Test
  public void sendsSpansToCorrectTopic() throws Exception {
    sender.close();
    sender = sender.toBuilder().topic("customzipkintopic").build();

    send(CLIENT_SPAN, CLIENT_SPAN).execute();

    assertThat(SpanBytesDecoder.JSON_V2.decodeList(readMessage("customzipkintopic")))
      .containsExactly(CLIENT_SPAN, CLIENT_SPAN);
  }

  @Test
  public void checkFalseWhenKafkaIsDown() throws Exception {
    broker.stop();

    // Make a new tracer that fails faster than 60 seconds
    sender.close();
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "100");
    sender = sender.toBuilder().overrides(overrides).build();

    CheckResult check = sender.check();
    assertThat(check.ok()).isFalse();
    assertThat(check.error()).isInstanceOf(TimeoutException.class);
  }

  @Test
  public void illegalToSendWhenClosed() {
    sender.close();

    assertThatThrownBy(() -> send(CLIENT_SPAN, CLIENT_SPAN).execute())
      .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldCloseKafkaProducerOnClose() throws Exception {
    send(CLIENT_SPAN, CLIENT_SPAN).execute();

    final ObjectName kafkaProducerMXBeanName = new ObjectName("kafka.producer:*");
    final Set<ObjectName> withProducers = ManagementFactory.getPlatformMBeanServer().queryNames(
      kafkaProducerMXBeanName, null);
    assertThat(withProducers).isNotEmpty();

    sender.close();

    final Set<ObjectName> withNoProducers = ManagementFactory.getPlatformMBeanServer().queryNames(
      kafkaProducerMXBeanName, null);
    assertThat(withNoProducers).isEmpty();
  }

  @Test
  public void shouldFailWhenMessageIsBiggerThanMaxSize() {
    sender.close();
    sender = sender.toBuilder().messageMaxBytes(1).build();

    assertThatThrownBy(() -> send(CLIENT_SPAN, CLIENT_SPAN).execute())
      .isInstanceOf(RecordTooLargeException.class);
  }

  /**
   * The output of toString() on {@link Sender} implementations appears in thread names created by
   * {@link AsyncReporter}. Since thread names are likely to be exposed in logs and other monitoring
   * tools, care should be taken to ensure the toString() output is a reasonable length and does not
   * contain sensitive information.
   */
  @Test
  public void toStringContainsOnlySummaryInformation() {
    assertThat(sender.toString()).isEqualTo(
      "KafkaSender{bootstrapServers=" + broker.getBrokerList().get() + ", topic=zipkin}"
    );
  }

  @Test
  public void checkFilterPropertiesProducerToAdminClient() {
    Properties producerProperties = new Properties();
    producerProperties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "100");
    producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      ByteArraySerializer.class.getName());
    producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      ByteArraySerializer.class.getName());
    producerProperties.put(ProducerConfig.BATCH_SIZE_CONFIG, "0");
    producerProperties.put(ProducerConfig.ACKS_CONFIG, "0");
    producerProperties.put(ProducerConfig.LINGER_MS_CONFIG, "500");
    producerProperties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");
    producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
    producerProperties.put(ProducerConfig.SECURITY_PROVIDERS_CONFIG, "sun.security.provider.Sun");

    Map<String, Object> filteredProperties =
      sender.filterPropertiesForAdminClient(producerProperties);

    assertThat(filteredProperties.size()).isEqualTo(2);
    assertThat(filteredProperties.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isNotNull();
    assertThat(filteredProperties.get(ProducerConfig.SECURITY_PROVIDERS_CONFIG)).isNotNull();
  }

  Call<Void> send(Span... spans) {
    SpanBytesEncoder bytesEncoder;
    switch (sender.encoding()) {
      case JSON:
        bytesEncoder = SpanBytesEncoder.JSON_V2;
        break;
      case THRIFT:
        bytesEncoder = SpanBytesEncoder.THRIFT;
        break;
      case PROTO3:
        bytesEncoder = SpanBytesEncoder.PROTO3;
        break;
      default:
        throw new UnsupportedOperationException("encoding: " + sender.encoding());
    }
    return sender.sendSpans(Stream.of(spans).map(bytesEncoder::encode).collect(toList()));
  }

  private byte[] readMessage(String topic) throws Exception {
    KafkaConsumer<byte[], byte[]> consumer = kafka.helper().createByteConsumer();
    return kafka.helper().consume(topic, consumer, 1)
      .get().stream().map(ConsumerRecord::value).findFirst().get();
  }

  private byte[] readMessage() throws Exception {
    return readMessage("zipkin");
  }
}

package io.softwarestrategies.scheduledevent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.softwarestrategies.scheduledevent.domain.DeliveryType;
import io.softwarestrategies.scheduledevent.domain.EventStatus;
import io.softwarestrategies.scheduledevent.domain.ScheduledEvent;
import io.softwarestrategies.scheduledevent.dto.KafkaEventMessage;
import io.softwarestrategies.scheduledevent.repository.ScheduledEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerServiceTest {

	@Mock
	private ScheduledEventRepository scheduledEventRepository;
	@Mock
	private KafkaProducerService kafkaProducerService;
	@Mock
	private Counter eventsPersistedCounter;
	@Mock
	private Counter deduplicationCacheHitCounter;
	@Mock
	private Counter deduplicationDbHitCounter;
	@Mock
	private Timer databaseBatchTimer;

	private KafkaConsumerService service;

	@BeforeEach
	void setUp() {
		service = new KafkaConsumerService(
				scheduledEventRepository,
				kafkaProducerService,
				eventsPersistedCounter,
				deduplicationCacheHitCounter,
				deduplicationDbHitCounter,
				databaseBatchTimer
		);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private KafkaEventMessage message(String messageId, String externalJobId) {
		KafkaEventMessage msg = new KafkaEventMessage();
		msg.setMessageId(messageId);
		msg.setExternalJobId(externalJobId);
		msg.setSource("test-source");
		msg.setScheduledAt(Instant.now().plusSeconds(3600));
		msg.setDeliveryType(DeliveryType.HTTP);
		msg.setDestination("http://example.com/callback");
		msg.setPayload("{\"key\":\"value\"}");
		msg.setMaxRetries(3);
		return msg;
	}

	private org.springframework.kafka.support.Acknowledgment mockAck() {
		return mock(org.springframework.kafka.support.Acknowledgment.class);
	}

	// -------------------------------------------------------------------------
	// Normal persist path
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_persistsNewEvent() {
		KafkaEventMessage msg = message("msg-1", "job-1");
		when(scheduledEventRepository.existsByUniqueKey(any(), any(), any())).thenReturn(false);
		var ack = mockAck();

		service.consumeIngestionBatch(List.of(msg), ack);

		ArgumentCaptor<ScheduledEvent> captor = ArgumentCaptor.forClass(ScheduledEvent.class);
		verify(scheduledEventRepository).save(captor.capture());
		assertThat(captor.getValue().getExternalJobId()).isEqualTo("job-1");
		assertThat(captor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
		verify(eventsPersistedCounter).increment();
		verify(ack).acknowledge();
	}

	// -------------------------------------------------------------------------
	// In-memory LRU cache deduplication
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_deduplicatesViaCacheOnSecondMessage() {
		KafkaEventMessage msg = message("msg-dup", "job-dup");
		when(scheduledEventRepository.existsByUniqueKey(any(), any(), any())).thenReturn(false);
		var ack = mockAck();

		// First call: message is new, gets persisted and cached
		service.consumeIngestionBatch(List.of(msg), ack);
		verify(scheduledEventRepository, times(1)).save(any());

		// Second call: same messageId — should be caught by in-memory cache
		service.consumeIngestionBatch(List.of(msg), ack);

		// No additional save — dedup stopped it
		verify(scheduledEventRepository, times(1)).save(any());
		// DB check should NOT be called for the second occurrence (cache hit)
		verify(scheduledEventRepository, times(1)).existsByUniqueKey(any(), any(), any());
		verify(deduplicationCacheHitCounter).increment();
	}

	// -------------------------------------------------------------------------
	// DB-level deduplication
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_deduplicatesViaDbCheck() {
		KafkaEventMessage msg = message("msg-new-instance", "job-db-dup");
		// Simulate: cache miss (different instance), DB says it already exists
		when(scheduledEventRepository.existsByUniqueKey(any(), any(), any())).thenReturn(true);
		var ack = mockAck();

		service.consumeIngestionBatch(List.of(msg), ack);

		verify(scheduledEventRepository, never()).save(any());
		verify(deduplicationDbHitCounter).increment();
	}

	// -------------------------------------------------------------------------
	// DataIntegrityViolationException — expected cross-instance race
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_constraintViolation_isSwallowedNotDlqd() {
		KafkaEventMessage msg = message("msg-race", "job-race");
		when(scheduledEventRepository.existsByUniqueKey(any(), any(), any())).thenReturn(false);
		when(scheduledEventRepository.save(any()))
				.thenThrow(new DataIntegrityViolationException("unique constraint"));
		var ack = mockAck();

		service.consumeIngestionBatch(List.of(msg), ack);

		// The constraint exception is expected — event must NOT be sent to DLQ
		verify(kafkaProducerService, never()).sendToDlq(any(), anyString());
		// Counter not incremented for constraint violations (dedup, not failure)
		verify(eventsPersistedCounter, never()).increment();
		verify(ack).acknowledge();
	}

	// -------------------------------------------------------------------------
	// Unexpected persist failure — routes to DLQ
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_unexpectedPersistFailure_sendsToDlq() {
		KafkaEventMessage msg = message("msg-err", "job-err");
		when(scheduledEventRepository.existsByUniqueKey(any(), any(), any())).thenReturn(false);
		when(scheduledEventRepository.save(any()))
				.thenThrow(new RuntimeException("DB connection lost"));
		var ack = mockAck();

		service.consumeIngestionBatch(List.of(msg), ack);

		verify(kafkaProducerService).sendToDlq(eq(msg), contains("Persist failed"));
		verify(ack).acknowledge();
	}

	// -------------------------------------------------------------------------
	// Empty/null batch
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_emptyList_acknowledgesImmediately() {
		var ack = mockAck();

		service.consumeIngestionBatch(List.of(), ack);

		verify(scheduledEventRepository, never()).existsByUniqueKey(any(), any(), any());
		verify(scheduledEventRepository, never()).save(any());
		verify(ack).acknowledge();
	}

	@Test
	void consumeIngestionBatch_nullList_acknowledgesImmediately() {
		var ack = mockAck();

		service.consumeIngestionBatch(null, ack);

		verify(scheduledEventRepository, never()).save(any());
		verify(ack).acknowledge();
	}

	// -------------------------------------------------------------------------
	// Multiple messages — partial success
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_partialFailure_persistsGoodAndDlqsBad() {
		KafkaEventMessage good = message("msg-good", "job-good");
		KafkaEventMessage bad = message("msg-bad", "job-bad");

		when(scheduledEventRepository.existsByUniqueKey(any(), any(), any())).thenReturn(false);
		// First call (good event) succeeds, second call (bad event) throws
		when(scheduledEventRepository.save(any()))
				.thenReturn(ScheduledEvent.builder().externalJobId("job-good").build())
				.thenThrow(new RuntimeException("timeout"));

		var ack = mockAck();

		service.consumeIngestionBatch(List.of(good, bad), ack);

		verify(eventsPersistedCounter, times(1)).increment();
		verify(kafkaProducerService, times(1)).sendToDlq(eq(bad), anyString());
		verify(ack).acknowledge();
	}

	// -------------------------------------------------------------------------
	// Entity conversion
	// -------------------------------------------------------------------------

	@Test
	void consumeIngestionBatch_setsDefaultMaxRetries_whenNull() {
		KafkaEventMessage msg = message("msg-default-retry", "job-default-retry");
		msg.setMaxRetries(null);
		when(scheduledEventRepository.existsByUniqueKey(any(), any(), any())).thenReturn(false);
		var ack = mockAck();

		service.consumeIngestionBatch(List.of(msg), ack);

		ArgumentCaptor<ScheduledEvent> captor = ArgumentCaptor.forClass(ScheduledEvent.class);
		verify(scheduledEventRepository).save(captor.capture());
		assertThat(captor.getValue().getMaxRetries()).isEqualTo(3);
	}
}

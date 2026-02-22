package io.softwarestrategies.scheduledevent.service;

import io.micrometer.core.instrument.Counter;
import io.softwarestrategies.scheduledevent.domain.DeliveryType;
import io.softwarestrategies.scheduledevent.domain.EventStatus;
import io.softwarestrategies.scheduledevent.domain.ScheduledEvent;
import io.softwarestrategies.scheduledevent.repository.ScheduledEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventPersistenceServiceTest {

	@Mock
	private ScheduledEventRepository scheduledEventRepository;
	@Mock
	private Counter eventsFailedCounter;

	private EventPersistenceService service;

	@BeforeEach
	void setUp() {
		service = new EventPersistenceService(scheduledEventRepository, eventsFailedCounter);
		ReflectionTestUtils.setField(service, "batchSize", 100);
		// Simulate @PostConstruct without network call
		ReflectionTestUtils.setField(service, "workerId", "test-worker-abc12345");
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private ScheduledEvent pendingEvent() {
		return ScheduledEvent.builder()
				.externalJobId("job-1")
				.source("test-source")
				.scheduledAt(Instant.now().minusSeconds(60))
				.deliveryType(DeliveryType.HTTP)
				.destination("http://example.com/callback")
				.payload("{\"key\":\"value\"}")
				.status(EventStatus.PENDING)
				.retryCount(0)
				.maxRetries(3)
				.build();
	}

	// -------------------------------------------------------------------------
	// fetchDueEvents
	// -------------------------------------------------------------------------

	@Test
	void fetchDueEvents_acquiresLocksOnReturnedEvents() {
		ScheduledEvent event = pendingEvent();
		when(scheduledEventRepository.findAndLockEventsForProcessing(any(), any(), eq(100)))
				.thenReturn(List.of(event));
		when(scheduledEventRepository.saveAll(anyList())).thenReturn(List.of(event));

		List<ScheduledEvent> result = service.fetchDueEvents();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(EventStatus.PROCESSING);
		assertThat(result.get(0).getLockedBy()).isEqualTo("test-worker-abc12345");
		assertThat(result.get(0).getLockExpiresAt()).isNotNull();
		verify(scheduledEventRepository).saveAll(anyList());
	}

	@Test
	void fetchDueEvents_whenNoEvents_doesNotCallSaveAll() {
		when(scheduledEventRepository.findAndLockEventsForProcessing(any(), any(), eq(100)))
				.thenReturn(List.of());

		List<ScheduledEvent> result = service.fetchDueEvents();

		assertThat(result).isEmpty();
		verify(scheduledEventRepository, never()).saveAll(anyList());
	}

	// -------------------------------------------------------------------------
	// rescheduleEvent
	// -------------------------------------------------------------------------

	@Test
	void rescheduleEvent_clearsLockAndSetsPending() {
		ScheduledEvent event = pendingEvent();
		event.acquireLock("worker-1", Instant.now().plusSeconds(300));

		service.rescheduleEvent(event);

		assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING);
		assertThat(event.getLockedBy()).isNull();
		assertThat(event.getLockExpiresAt()).isNull();
		verify(scheduledEventRepository).save(event);
	}

	// -------------------------------------------------------------------------
	// markEventCompleted
	// -------------------------------------------------------------------------

	@Test
	void markEventCompleted_setsCompletedStatusAndClearsLock() {
		ScheduledEvent event = pendingEvent();
		event.acquireLock("worker-1", Instant.now().plusSeconds(300));

		service.markEventCompleted(event);

		assertThat(event.getStatus()).isEqualTo(EventStatus.COMPLETED);
		assertThat(event.getExecutedAt()).isNotNull();
		assertThat(event.getLockedBy()).isNull();
		assertThat(event.getLockExpiresAt()).isNull();
		verify(scheduledEventRepository).save(event);
	}

	// -------------------------------------------------------------------------
	// handleDeliveryFailure — retry path
	// -------------------------------------------------------------------------

	@Test
	void handleDeliveryFailure_whenCanRetry_setsBackToPending() {
		ScheduledEvent event = pendingEvent();  // retryCount=0, maxRetries=3
		EventDeliveryService.DeliveryResult failure =
				EventDeliveryService.DeliveryResult.ofFailure("timeout", true);

		service.handleDeliveryFailure(event, failure);

		// After markFailed: retryCount becomes 1, still < maxRetries(3), so PENDING
		assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING);
		assertThat(event.getRetryCount()).isEqualTo(1);
		assertThat(event.getLastError()).isEqualTo("timeout");
		verify(scheduledEventRepository).save(event);
		verify(eventsFailedCounter).increment();
	}

	// -------------------------------------------------------------------------
	// handleDeliveryFailure — dead letter path
	// -------------------------------------------------------------------------

	@Test
	void handleDeliveryFailure_whenNoRetriesLeft_movesToDeadLetter() {
		ScheduledEvent event = ScheduledEvent.builder()
				.externalJobId("job-dl")
				.source("test-source")
				.scheduledAt(Instant.now().minusSeconds(60))
				.deliveryType(DeliveryType.HTTP)
				.destination("http://example.com/callback")
				.payload("{}")
				.status(EventStatus.PROCESSING)
				.retryCount(3)   // already at max
				.maxRetries(3)
				.build();

		EventDeliveryService.DeliveryResult failure =
				EventDeliveryService.DeliveryResult.ofFailure("connection refused", false);

		service.handleDeliveryFailure(event, failure);

		// markFailed increments retryCount to 4, canRetry() is false -> DEAD_LETTER
		assertThat(event.getStatus()).isEqualTo(EventStatus.DEAD_LETTER);
		assertThat(event.getRetryCount()).isEqualTo(4);
		verify(scheduledEventRepository).save(event);
		verify(eventsFailedCounter).increment();
	}

	// -------------------------------------------------------------------------
	// handleDeliveryFailure — error message truncation
	// -------------------------------------------------------------------------

	@Test
	void handleDeliveryFailure_truncatesLongErrorMessages() {
		ScheduledEvent event = pendingEvent();
		String longError = "x".repeat(5000);
		EventDeliveryService.DeliveryResult failure =
				EventDeliveryService.DeliveryResult.ofFailure(longError, true);

		service.handleDeliveryFailure(event, failure);

		assertThat(event.getLastError()).hasSize(4000);
	}

	// -------------------------------------------------------------------------
	// handleDeliveryFailure — null error
	// -------------------------------------------------------------------------

	@Test
	void handleDeliveryFailure_handlesNullError() {
		ScheduledEvent event = pendingEvent();
		EventDeliveryService.DeliveryResult failure =
				EventDeliveryService.DeliveryResult.ofFailure(null, true);

		service.handleDeliveryFailure(event, failure);

		assertThat(event.getLastError()).isNull();
		verify(scheduledEventRepository).save(event);
	}
}

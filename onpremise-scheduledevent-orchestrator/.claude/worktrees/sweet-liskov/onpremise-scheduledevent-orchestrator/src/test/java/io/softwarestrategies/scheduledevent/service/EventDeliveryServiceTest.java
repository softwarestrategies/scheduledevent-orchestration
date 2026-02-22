package io.softwarestrategies.scheduledevent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.softwarestrategies.scheduledevent.domain.DeliveryType;
import io.softwarestrategies.scheduledevent.domain.ScheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventDeliveryServiceTest {

	@Mock private RestClient restClient;
	@Mock private KafkaProducerService kafkaProducerService;
	@Mock private Counter httpDeliveryCounter;
	@Mock private Counter kafkaDeliveryCounter;

	// RestClient fluent chain — each step mocked explicitly
	@Mock private RestClient.RequestBodyUriSpec postSpec;
	@Mock private RestClient.RequestBodySpec bodySpec;
	@Mock private RestClient.ResponseSpec responseSpec;

	private EventDeliveryService service;

	@BeforeEach
	void setUp() {
		// Use a real SimpleMeterRegistry so Timer.start()/sample.stop() work without mocking statics
		MeterRegistry registry = new SimpleMeterRegistry();
		Timer eventDeliveryTimer = Timer.builder("test.delivery").register(registry);

		service = new EventDeliveryService(restClient, kafkaProducerService,
				httpDeliveryCounter, kafkaDeliveryCounter, eventDeliveryTimer);

		lenient().when(restClient.post()).thenReturn(postSpec);
		lenient().when(postSpec.uri(anyString())).thenReturn(bodySpec);
		lenient().when(bodySpec.contentType(any())).thenReturn(bodySpec);
		lenient().when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
		lenient().when(bodySpec.retrieve()).thenReturn(responseSpec);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private ScheduledEvent httpEvent() {
		return ScheduledEvent.builder()
				.externalJobId("job-1")
				.source("test-source")
				.scheduledAt(Instant.now())
				.deliveryType(DeliveryType.HTTP)
				.destination("http://example.com/callback")
				.payload("{\"key\":\"value\"}")
				.maxRetries(3)
				.build();
	}

	private ScheduledEvent kafkaEvent() {
		return ScheduledEvent.builder()
				.externalJobId("job-2")
				.source("test-source")
				.scheduledAt(Instant.now())
				.deliveryType(DeliveryType.KAFKA)
				.destination("my-external-topic")
				.payload("{\"key\":\"value\"}")
				.maxRetries(3)
				.build();
	}

	// -------------------------------------------------------------------------
	// HTTP delivery — success
	// -------------------------------------------------------------------------

	@Test
	void deliverEvent_httpSuccess_returnsSuccess() {
		when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

		EventDeliveryService.DeliveryResult result = service.deliverEvent(httpEvent());

		assertThat(result.success()).isTrue();
		assertThat(result.error()).isNull();
		verify(httpDeliveryCounter).increment();
	}

	// -------------------------------------------------------------------------
	// HTTP delivery — non-retriable 4xx, no retries
	// -------------------------------------------------------------------------

	@Test
	void deliverEvent_http400_returnsNonRetriableFailureWithoutRetry() {
		when(responseSpec.toBodilessEntity()).thenThrow(new RestClientResponseException(
				"Bad Request", HttpStatus.BAD_REQUEST.value(), "Bad Request",
				null, null, StandardCharsets.UTF_8));

		EventDeliveryService.DeliveryResult result = service.deliverEvent(httpEvent());

		assertThat(result.success()).isFalse();
		assertThat(result.retriable()).isFalse();
		// Only one attempt — 400 is not retriable
		verify(bodySpec, times(1)).retrieve();
	}

	// -------------------------------------------------------------------------
	// HTTP delivery — retriable 503 exhausts MAX_RETRIES (2)
	// -------------------------------------------------------------------------

	@Test
	void deliverEvent_http503_exhaustsRetriesAndReturnsRetriableFailure() {
		when(responseSpec.toBodilessEntity()).thenThrow(new RestClientResponseException(
				"Service Unavailable", HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable",
				null, null, StandardCharsets.UTF_8));

		EventDeliveryService.DeliveryResult result = service.deliverEvent(httpEvent());

		assertThat(result.success()).isFalse();
		assertThat(result.retriable()).isTrue();
		// MAX_RETRIES=2 means 3 total attempts (0,1,2)
		verify(bodySpec, times(3)).retrieve();
	}

	// -------------------------------------------------------------------------
	// HTTP delivery — 429 Too Many Requests is retriable
	// -------------------------------------------------------------------------

	@Test
	void deliverEvent_http429_isRetriable() {
		when(responseSpec.toBodilessEntity()).thenThrow(new RestClientResponseException(
				"Too Many Requests", 429, "Too Many Requests",
				null, null, StandardCharsets.UTF_8));

		EventDeliveryService.DeliveryResult result = service.deliverEvent(httpEvent());

		assertThat(result.retriable()).isTrue();
		verify(bodySpec, times(3)).retrieve();
	}

	// -------------------------------------------------------------------------
	// HTTP delivery — 404 is not retriable
	// -------------------------------------------------------------------------

	@Test
	void deliverEvent_http404_isNotRetriable() {
		when(responseSpec.toBodilessEntity()).thenThrow(new RestClientResponseException(
				"Not Found", 404, "Not Found",
				null, null, StandardCharsets.UTF_8));

		EventDeliveryService.DeliveryResult result = service.deliverEvent(httpEvent());

		assertThat(result.retriable()).isFalse();
		verify(bodySpec, times(1)).retrieve();
	}

	// -------------------------------------------------------------------------
	// Kafka delivery — success
	// -------------------------------------------------------------------------

	@Test
	void deliverEvent_kafkaSuccess_returnsSuccess() {
		when(kafkaProducerService.sendToExternalTopic(anyString(), anyString(), any()))
				.thenReturn(CompletableFuture.completedFuture(null));

		EventDeliveryService.DeliveryResult result = service.deliverEvent(kafkaEvent());

		assertThat(result.success()).isTrue();
		assertThat(result.error()).isNull();
		verify(kafkaDeliveryCounter).increment();
	}

	// -------------------------------------------------------------------------
	// Kafka delivery — failure is always retriable
	// -------------------------------------------------------------------------

	@Test
	void deliverEvent_kafkaFailure_returnsRetriableFailure() {
		when(kafkaProducerService.sendToExternalTopic(anyString(), anyString(), any()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("Broker unavailable")));

		EventDeliveryService.DeliveryResult result = service.deliverEvent(kafkaEvent());

		assertThat(result.success()).isFalse();
		assertThat(result.retriable()).isTrue();
		assertThat(result.error()).contains("Kafka delivery failed");
	}

	// -------------------------------------------------------------------------
	// DeliveryResult factory methods
	// -------------------------------------------------------------------------

	@Test
	void deliveryResult_ofSuccess_hasCorrectFields() {
		EventDeliveryService.DeliveryResult result = EventDeliveryService.DeliveryResult.ofSuccess();
		assertThat(result.success()).isTrue();
		assertThat(result.error()).isNull();
		assertThat(result.retriable()).isFalse();
	}

	@Test
	void deliveryResult_ofFailure_hasCorrectFields() {
		EventDeliveryService.DeliveryResult result =
				EventDeliveryService.DeliveryResult.ofFailure("timeout", true);
		assertThat(result.success()).isFalse();
		assertThat(result.error()).isEqualTo("timeout");
		assertThat(result.retriable()).isTrue();
	}
}

package io.softwarestrategies.scheduledevent.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.softwarestrategies.scheduledevent.event.ScheduledEvent;
import io.softwarestrategies.scheduledevent.service.ScheduledEventService;
import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
public class ScheduledEventController {

	private final ScheduledEventService scheduledEventService;

	@PostMapping("/api/v1/scheduled-events")
	public ResponseEntity<String> scheduleAnEvent(@RequestBody ScheduledEvent scheduledEvent) {
		scheduledEventService.scheduleEvent(scheduledEvent);
		return ResponseEntity.ok().build();
	}
}

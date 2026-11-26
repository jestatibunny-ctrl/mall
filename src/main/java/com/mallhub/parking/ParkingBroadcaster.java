package com.mallhub.parking;

import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Pushes current parking numbers to every browser subscribed to /topic/parking. */
@Component
public class ParkingBroadcaster {

    public record ZoneUpdate(long zoneId, int available, int total) {}

    private final ParkingService parking;
    private final SimpMessagingTemplate messaging;

    public ParkingBroadcaster(ParkingService parking, SimpMessagingTemplate messaging) {
        this.parking = parking;
        this.messaging = messaging;
    }

    public void broadcast() {
        List<ZoneUpdate> updates = parking.zones().stream()
                .map(z -> new ZoneUpdate(z.zoneId(), z.availableSlots(), z.totalSlots()))
                .toList();
        messaging.convertAndSend("/topic/parking", updates);
    }
}

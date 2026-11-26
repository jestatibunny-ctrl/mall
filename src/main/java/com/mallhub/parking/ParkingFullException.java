package com.mallhub.parking;

public class ParkingFullException extends RuntimeException {
    public ParkingFullException() {
        super("This parking zone is full");
    }
}

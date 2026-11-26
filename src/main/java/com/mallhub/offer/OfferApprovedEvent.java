package com.mallhub.offer;

public record OfferApprovedEvent(long offerId, String title, String storeName, String mallName) {}

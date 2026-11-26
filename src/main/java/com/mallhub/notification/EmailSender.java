package com.mallhub.notification;

/** Extension point: swap LoggingEmailSender for a real SMTP / provider implementation. */
public interface EmailSender {
    void send(String to, String subject, String body);
}

package com.mallhub;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MallhubApplication {

    // Some systems (e.g. Windows in India) report the old zone name "Asia/Calcutta", which newer
    // PostgreSQL versions reject. Setting the modern name before anything connects avoids the crash.
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    public static void main(String[] args) {
        SpringApplication.run(MallhubApplication.class, args);
    }
}

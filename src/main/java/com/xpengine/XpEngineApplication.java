package com.xpengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class XpEngineApplication {
    public static void main(String[] args) {
        // Windows reports India as the legacy "Asia/Calcutta", which Postgres 16 rejects.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(XpEngineApplication.class, args);
    }
}
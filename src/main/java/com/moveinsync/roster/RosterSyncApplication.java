package com.moveinsync.roster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // runs the outbox relay in the background
public class RosterSyncApplication {

	public static void main(String[] args) {
		SpringApplication.run(RosterSyncApplication.class, args);
	}

}

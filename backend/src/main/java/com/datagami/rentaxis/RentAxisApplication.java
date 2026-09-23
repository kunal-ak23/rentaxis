package com.datagami.rentaxis;

import com.datagami.rentaxis.config.AppTimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RentAxisApplication {

	public static void main(String[] args) {
		// Before anything reads the clock: "today" is the UAE's (see AppTimeZone).
		AppTimeZone.applyFromSystem();
		SpringApplication.run(RentAxisApplication.class, args);
	}

}

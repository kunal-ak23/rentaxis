package com.datagami.rentaxis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RentAxisApplication {

	public static void main(String[] args) {
		SpringApplication.run(RentAxisApplication.class, args);
	}

}

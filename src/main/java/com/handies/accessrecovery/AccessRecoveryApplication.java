package com.handies.accessrecovery;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AccessRecoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessRecoveryApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "app.seed-demo-reservations", havingValue = "true", matchIfMissing = true)
    CommandLineRunner seedReservations(ReservationRepository reservations) {
        return args -> {
            for (Reservation reservation : List.of(
                    new Reservation(1001L, "101"),
                    new Reservation(1002L, "102"),
                    new Reservation(1003L, "103"))) {
                if (!reservations.existsById(reservation.getId())) {
                    reservations.save(reservation);
                }
            }
        };
    }
}

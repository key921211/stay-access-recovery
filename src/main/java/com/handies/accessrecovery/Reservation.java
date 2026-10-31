package com.handies.accessrecovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Reservation {

    @Id
    private Long id;

    @Column(nullable = false, length = 30)
    private String roomNumber;

    private Instant checkedInAt;

    protected Reservation() {
    }

    public Reservation(Long id, String roomNumber) {
        this.id = id;
        this.roomNumber = roomNumber;
    }

    public Long getId() {
        return id;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public void checkIn(Instant now) {
        if (checkedInAt == null) {
            checkedInAt = now;
        }
    }
}

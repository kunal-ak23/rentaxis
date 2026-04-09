package com.datagami.rentaxis.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.Instant;

@Data
@AllArgsConstructor
public class SlotDTO {
    private Instant start;
    private Instant end;
    private boolean available;
}

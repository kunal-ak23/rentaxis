package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.appversion.AppVersionService;
import com.datagami.rentaxis.core.appversion.AppVersionService.AppVersionView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Controller-level cover for the public GET. Focuses on the one behaviour the
 * controller adds over the service: it must fail open even when the service
 * itself throws (e.g. the database is down), returning 200 + the permissive
 * payload rather than a 500. A version check that could 500 would let an outage
 * hard-block every app that treats "below minSupportedBuild" as a lockout.
 */
@ExtendWith(MockitoExtension.class)
class AppVersionControllerTest {

    @Mock
    AppVersionService service;

    @InjectMocks
    AppVersionController controller;

    @Test
    void get_passesThroughServiceResult() {
        when(service.resolve("RENTER", "ANDROID"))
                .thenReturn(new AppVersionView(0, 3, "1.2.0", ""));

        ResponseEntity<AppVersionView> resp = controller.get("RENTER", "ANDROID");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().latestBuild()).isEqualTo(3);
    }

    @Test
    void get_whenServiceThrows_failsOpenWith200AndPermissivePayload() {
        when(service.resolve(any(), any()))
                .thenThrow(new RuntimeException("db unreachable"));

        ResponseEntity<AppVersionView> resp = controller.get("RENTER", "ANDROID");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().minSupportedBuild()).isEqualTo(0);
        assertThat(resp.getBody().latestBuild()).isEqualTo(0);
        assertThat(resp.getBody().latestVersionName()).isEqualTo("");
        assertThat(resp.getBody().storeUrl()).isEqualTo("");
    }
}

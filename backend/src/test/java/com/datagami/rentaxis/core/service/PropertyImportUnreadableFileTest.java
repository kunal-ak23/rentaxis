package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BulkPropertyImportResultDTO;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Break-it R3 data3 F4 (review round 2): Import property with units reported
 * "Failed to read CSV file: " + the reader's exception text. It now says what to do
 * and gives a reference; the detail is only in the log. Nothing is saved.
 */
class PropertyImportUnreadableFileTest {

    @Test
    void anUnreadableFileIsASentenceWithAReferenceNotTheExceptionText() throws Exception {
        PropertyRepository properties = mock(PropertyRepository.class);
        PropertyService service = new PropertyService(properties, mock(UnitRepository.class), mock(BuildingRepository.class),
                mock(com.datagami.rentaxis.core.security.PropertyScope.class), mock(UserService.class),
                mock(com.datagami.rentaxis.core.service.ledger.PropertyAccountService.class));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenThrow(new IOException("/var/lib/rentaxis/tmp/upload_7f3a.tmp (Permission denied)"));

        BulkPropertyImportResultDTO r = service.importPropertyWithUnits(file, "Marina", null, "DUBAI", null, "RESIDENTIAL", null);

        assertThat(r.getErrors()).singleElement().asString()
                .startsWith("The file could not be read as a CSV")
                .containsPattern("reference [0-9A-F]{8}$")
                .doesNotContain("/var/lib").doesNotContain("Permission denied");
        verify(properties, never()).save(any());
    }
}

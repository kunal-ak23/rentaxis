package com.datagami.rentaxis.core.email.api;

import com.datagami.rentaxis.core.email.dispatch.EmailPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/email")
@RequiredArgsConstructor
@Slf4j
public class UnsubscribeController {

    private final EmailPreferenceService prefs;

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam("token") String token) {
        return prefs.disableMarketingByToken(token)
                .map(uid -> {
                    log.info("email.unsubscribe user_id={} source=link", uid);
                    return ResponseEntity.ok("<html><body><h2>You've been unsubscribed</h2>" +
                            "<p>You will no longer receive marketing emails from RentAxis. " +
                            "You will continue to receive transactional emails (lease, payment, etc).</p></body></html>");
                })
                .orElseGet(() -> ResponseEntity.status(404).body("<html><body>Unknown token</body></html>"));
    }
}

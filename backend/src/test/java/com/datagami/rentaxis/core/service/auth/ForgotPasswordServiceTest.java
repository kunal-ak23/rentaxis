package com.datagami.rentaxis.core.service.auth;

import com.datagami.rentaxis.core.service.UserService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The per-address limit of the public "forgot password": silent, per normalised email. */
class ForgotPasswordServiceTest {

    private final UserService users = mock(UserService.class);
    private final ForgotPasswordService service = new ForgotPasswordService(users, Runnable::run);

    @Test
    void threeLinksAnHourPerAddressThenSilence() {
        for (int i = 0; i < 5; i++) service.request(i % 2 == 0 ? "Someone@Example.com" : " someone@example.com ");
        verify(users, times(3)).sendForgottenPasswordLink("someone@example.com");
    }

    @Test
    void eachAddressHasItsOwnLimit() {
        for (int i = 0; i < 3; i++) service.request("a@example.com");
        service.request("b@example.com");
        verify(users, times(3)).sendForgottenPasswordLink("a@example.com");
        verify(users, times(1)).sendForgottenPasswordLink("b@example.com");
    }

    @Test
    void somethingThatIsNotAnEmailIsIgnored() {
        service.request(null);
        service.request("");
        service.request("no-at-sign");
        verify(users, never()).sendForgottenPasswordLink(anyString());
    }

    @Test
    void aFailureInTheBackgroundIsSwallowedNotReported() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(users).sendForgottenPasswordLink(anyString());
        service.request("c@example.com"); // must not throw: the caller already has its answer
    }
}

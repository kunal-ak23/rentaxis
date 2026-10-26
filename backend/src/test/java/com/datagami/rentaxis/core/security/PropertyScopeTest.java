package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.api.exception.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PropertyScopeTest {

    private final LeaseAccessPolicy policy = mock(LeaseAccessPolicy.class);
    private final PropertyScope scope = new PropertyScope(policy);
    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();

    private static void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantWideRolesAreUnrestrictedOrNarrowedToWhatTheyNamed() {
        as("ACCOUNTANT");
        assertThat(scope.requireSelection(List.of())).isEqualTo(new PropertyScope.Selection(true, List.of(), false));
        assertThat(scope.requireSelection(null)).isEqualTo(new PropertyScope.Selection(true, List.of(), false));
        assertThat(scope.requireSelection(List.of(p1, p1))).isEqualTo(new PropertyScope.Selection(false, List.of(p1), false));
        assertThat(scope.requireSelection(Arrays.asList(p1, null, p2))).isEqualTo(new PropertyScope.Selection(false, List.of(p1, p2), false));
        assertThat(scope.requireSelection(Arrays.asList((UUID) null))).isEqualTo(new PropertyScope.Selection(true, List.of(), false));
        as("TENANT_ADMIN");
        assertThat(scope.requireSelection(List.of()).unrestricted()).isTrue();
    }

    @Test
    void aManagerGetsTheAssignmentA404ForAForeignIdAndEmptyWhenAssignedNothing() {
        as("PROPERTY_MANAGER");
        when(policy.visiblePropertyIds()).thenReturn(List.of(p1, p2));
        assertThat(scope.requireSelection(null)).isEqualTo(new PropertyScope.Selection(false, List.of(p1, p2), false));
        assertThat(scope.requireSelection(List.of(p2)).ids()).containsExactly(p2);
        assertThat(scope.requireSelection(List.of(p2, p1, p2)).ids()).containsExactly(p2, p1);
        assertThatThrownBy(() -> scope.requireSelection(List.of(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class).hasMessage("Property not found");
        // One foreign id among assigned ones is still a 404, not a quiet narrowing.
        assertThatThrownBy(() -> scope.requireSelection(List.of(p1, UUID.randomUUID()))).isInstanceOf(NotFoundException.class);
        when(policy.visiblePropertyIds()).thenReturn(List.of());
        assertThat(scope.requireSelection(List.of())).isEqualTo(new PropertyScope.Selection(false, List.of(), true));
        assertThatThrownBy(() -> scope.requireSelection(List.of(p1))).isInstanceOf(NotFoundException.class);
        assertThat("requireSelection").matches("^(require.*|filter.*|canManage|canRead|canAccess.*)$");
    }

    @Test
    void sqlIdsAreNeverAnEmptyInList() {
        as("ACCOUNTANT");
        PropertyScope.Selection all = scope.requireSelection(List.of());
        assertThat(all.sqlIds()).containsExactly(new UUID(0L, 0L));
        assertThat(scope.requireSelection(List.of(p1)).sqlIds()).containsExactly(p1);
        as("PROPERTY_MANAGER");
        when(policy.visiblePropertyIds()).thenReturn(List.of());
        assertThat(scope.requireSelection(null).sqlIds()).containsExactly(new UUID(0L, 0L));
    }

    @Test
    void aSelectionsIdsCannotBeChangedAfterwards() {
        as("ACCOUNTANT");
        PropertyScope.Selection s = scope.requireSelection(List.of(p1));
        assertThatThrownBy(() -> s.ids().add(p2)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSelectionIsExactlyOneOfAllNoneOrListed() {
        // "Listed" with no ids would bind the sentinel and silently select nothing; it must say empty instead.
        assertThatThrownBy(() -> new PropertyScope.Selection(false, List.of(), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyScope.Selection(true, List.of(p1), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyScope.Selection(false, List.of(p1), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyScope.Selection(true, List.of(), true)).isInstanceOf(IllegalArgumentException.class);
    }
}

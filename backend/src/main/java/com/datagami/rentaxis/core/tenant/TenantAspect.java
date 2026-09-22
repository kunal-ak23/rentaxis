package com.datagami.rentaxis.core.tenant;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Switches Hibernate's {@code tenantFilter} on before any call into a repository,
 * so that a query written without a tenant clause still answers for one tenant
 * only. Every tenant-scoped read in this codebase rests on it.
 *
 * <p><b>The pointcut does cover inherited Spring Data methods</b> —
 * {@code findAll}, {@code count}, {@code findById}, {@code existsById},
 * {@code save}, {@code findAll(Pageable)} — and not only the finders our own
 * interfaces declare. That is worth writing down because reading the expression
 * suggests the opposite: at runtime the join point for {@code tickets.count()}
 * reports its declaring type as {@code org.springframework.data.repository.CrudRepository},
 * which is not in the package this expression names. It matches anyway because
 * Spring does not match on {@code Method#getDeclaringClass} alone: for a proxied
 * bean, {@code AspectJExpressionPointcut} re-resolves the method against a
 * composite of <em>all</em> interfaces the proxy implements before handing it to
 * AspectJ ("the most specific interface possible for inherited methods to be
 * considered for sub-interface matches … in particular for proxy classes"), and a
 * Spring Data repository bean is a JDK proxy implementing the repository interface
 * in {@code domain.repository}. Driving the same pointcut outside the container
 * against the bare interface skips that branch and reports no match — a plausible
 * but wrong reading of what the application does. {@code TenantAspectIT} pins the
 * behaviour, including a direct assertion that one inherited call is enough to
 * leave the filter enabled on the session.
 *
 * <p><b>What it does not do</b> is make the filter outlive the session it was
 * enabled on. {@code entityManager} is the shared transaction-aware proxy: inside
 * a transaction it resolves to the bound session, which is the same session the
 * query then runs on; with no transaction in progress each call gets a fresh
 * EntityManager, so the filter is enabled on one session and the query runs on
 * another. That is why every tenant-scoped service read is {@code @Transactional},
 * and why one that cannot be needs an explicit tenant comparison instead.
 * {@code TenantAspectIT.aReadWithNoTransactionOfItsOwnIsNotFiltered} pins that too.
 *
 * <p>A null tenant context leaves the filter off on purpose: SUPER_ADMIN and the
 * bootstrap paths read across tenants.
 */
@Aspect
@Component
public class TenantAspect {

    private final EntityManager entityManager;

    public TenantAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Before("execution(* com.datagami.rentaxis.domain.repository..*(..))")
    public void enableTenantFilter() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
        }
    }
}

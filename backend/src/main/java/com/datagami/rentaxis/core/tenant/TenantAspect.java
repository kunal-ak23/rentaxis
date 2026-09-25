package com.datagami.rentaxis.core.tenant;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p><b>Outside a transaction it opens one.</b> {@code entityManager} is the shared
 * transaction-aware proxy: inside a transaction it resolves to the bound session, which
 * is the same session the query then runs on; with no transaction (and, since scale PR A,
 * no open-session-in-view either) each call would get a fresh EntityManager, so a filter
 * enabled here would sit on one session while the query ran on another — an unfiltered,
 * cross-tenant read. So a repository call made with a tenant in context and no
 * transaction in progress runs inside a transaction of its own (REQUIRED, read-write so
 * a stray {@code save} still flushes), with the filter enabled on that transaction's
 * session. Entities it returns are detached afterwards: a service that maps lazy
 * associations must itself be {@code @Transactional}.
 * {@code TenantAspectIT.aReadWithNoTransactionOfItsOwnIsFilteredToo} pins it.
 *
 * <p>A null tenant context leaves the filter off on purpose: SUPER_ADMIN and the
 * bootstrap paths read across tenants.
 */
@Aspect
@Component
public class TenantAspect {

    private final EntityManager entityManager;
    private final TransactionTemplate standalone;

    public TenantAspect(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.standalone = new TransactionTemplate(transactionManager);
    }

    @Around("execution(* com.datagami.rentaxis.domain.repository..*(..))")
    public Object enableTenantFilter(ProceedingJoinPoint call) throws Throwable {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            return call.proceed();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            enable(tenantId);
            return call.proceed();
        }
        try {
            return standalone.execute(status -> {
                enable(tenantId);
                try {
                    return call.proceed();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new CheckedWrapper(t);
                }
            });
        } catch (CheckedWrapper w) {
            throw w.getCause();
        }
    }

    private void enable(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
    }

    private static final class CheckedWrapper extends RuntimeException {
        CheckedWrapper(Throwable cause) {
            super(cause);
        }
    }
}

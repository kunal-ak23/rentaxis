package com.datagami.rentaxis.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every controller method a PROPERTY_MANAGER can call with a property-bound id must
 * reach {@code PropertyScope} or {@code LeaseAccessPolicy}.
 *
 * <p>Round-5 audit (P1-3..P1-6, #72): the same missing call recurred on a dozen
 * controllers because nothing but a reviewer's memory asked for it. This reads the
 * compiled bytecode (Spring's repackaged ASM, no extra dependency), builds the call
 * graph from each handler through our own classes — lambdas and method references
 * included — and fails when a handler that takes an id never gets to either helper.</p>
 *
 * <p>Pragmatic, not a proof: reaching the helper on some path is not proof it guards
 * the right object, and ids inside request bodies are not seen. Reviewed exceptions
 * live in {@link #EXEMPT} with the reason; the test also fails when an exemption is
 * no longer needed, so the list cannot rot.</p>
 */
class PropertyScopeCoverageTest {

    private static final String ROOT = "com/datagami/rentaxis/";
    private static final Set<String> HELPERS = Set.of(
            ROOT + "core/security/PropertyScope",
            ROOT + "core/security/LeaseAccessPolicy");
    private static final int MAX_DEPTH = 6;

    /** Parameter names that name a property-bound object. {@code id} is the controller's own resource. */
    private static final Pattern ID_PARAM = Pattern.compile(
            "(?i)^(id|.*(property|lease|unit|ticket|listing|building|meeting|pass|walkin|attachment"
                    + "|contact|interaction|settlement|deduction|opportunity|booking|amenity|spot|profile).*id)$");

    /**
     * Reviewed: reachable by PROPERTY_MANAGER, takes an id, and is deliberately not
     * property-scoped. Key is {@code SimpleClassName#method}.
     */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            // The caller's own row: NotificationService.markAsRead looks it up by (id, caller).
            Map.entry("NotificationController#markAsRead", "caller-owned notification, findByIdAndUserIdUnfiltered"),
            // A renter has no property FK; whether a manager's renter directory is
            // property-scoped is the open product question noted under audit #72.
            // The renter's leases (/renters/{id}/leases) ARE filtered by LeaseAccessPolicy.
            Map.entry("RenterController#getRenterById", "renters are tenant-wide by product decision (open, #72)")
    );

    private static final Map<String, ClassInfo> CLASSES = new HashMap<>();

    @BeforeAll
    static void loadBytecode() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource r : resolver.getResources("classpath*:" + ROOT + "**/*.class")) {
            try (InputStream in = r.getInputStream()) {
                ClassInfo info = new ClassInfo();
                new ClassReader(in).accept(info, ClassReader.SKIP_FRAMES);
                CLASSES.putIfAbsent(info.name, info);
            }
        }
        assertThat(CLASSES).as("bytecode of the application classes").hasSizeGreaterThan(200);
    }

    @Test
    void everyPropertyManagerReachableHandlerTakingAnIdRoutesThroughThePropertyScope() throws Exception {
        Map<String, String> uncovered = new TreeMap<>();
        Set<String> examined = new TreeSet<>();
        Set<String> covered = new TreeSet<>();

        for (ClassInfo c : CLASSES.values()) {
            if (!c.name.startsWith(ROOT + "api/") && !c.name.startsWith(ROOT + "core/email/api/")) continue;
            Class<?> type = Class.forName(c.name.replace('/', '.'), false, getClass().getClassLoader());
            if (!type.isAnnotationPresent(RestController.class)) continue;

            for (Method m : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) continue;
                if (!reachableByPropertyManager(type, m)) continue;
                List<String> ids = idParameters(m);
                if (ids.isEmpty()) continue;

                String key = type.getSimpleName() + "#" + m.getName();
                examined.add(key);
                if (reachesHelper(c.name, m.getName(), org.springframework.asm.Type.getMethodDescriptor(m))) {
                    covered.add(key);
                } else {
                    uncovered.put(key, String.join(",", ids));
                }
            }
        }

        assertThat(examined).as("handlers examined").hasSizeGreaterThan(40);

        Map<String, String> unexplained = new TreeMap<>(uncovered);
        unexplained.keySet().removeAll(EXEMPT.keySet());
        assertThat(unexplained)
                .as("PM-reachable handlers taking an id that never reach PropertyScope/LeaseAccessPolicy "
                        + "(route them through PropertyScope, or add a reviewed EXEMPT entry)")
                .isEmpty();

        Set<String> stale = new TreeSet<>(EXEMPT.keySet());
        stale.removeAll(uncovered.keySet());
        assertThat(stale).as("EXEMPT entries that are now covered or no longer exist; remove them").isEmpty();
    }

    /** The handler's effective @PreAuthorize lets a PROPERTY_MANAGER in. */
    private static boolean reachableByPropertyManager(Class<?> type, Method m) {
        PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
        if (pa == null) pa = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
        if (pa == null) return false; // public or filter-gated routes; not role-bound
        String expr = pa.value();
        return expr.contains("PROPERTY_MANAGER") || expr.contains("isAuthenticated()");
    }

    private static List<String> idParameters(Method m) {
        List<String> ids = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            String name = null;
            PathVariable pv = p.getAnnotation(PathVariable.class);
            RequestParam rp = p.getAnnotation(RequestParam.class);
            if (pv != null) name = !pv.value().isEmpty() ? pv.value() : !pv.name().isEmpty() ? pv.name() : p.getName();
            else if (rp != null) name = !rp.value().isEmpty() ? rp.value() : !rp.name().isEmpty() ? rp.name() : p.getName();
            if (name == null) continue;
            boolean idType = p.getType() == UUID.class || p.getType() == String.class;
            if (idType && ID_PARAM.matcher(name).matches()) ids.add(name);
        }
        return ids;
    }

    private static boolean reachesHelper(String owner, String name, String desc) {
        Deque<Object[]> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(new Object[]{new Call(owner, name, desc), 0});
        while (!queue.isEmpty()) {
            Object[] next = queue.poll();
            Call call = (Call) next[0];
            int depth = (int) next[1];
            if (HELPERS.contains(call.owner)) return true;
            if (depth >= MAX_DEPTH || !seen.add(call.key())) continue;
            for (Call target : resolve(call)) {
                MethodInfo mi = CLASSES.get(target.owner).methods.get(target.name + target.desc);
                if (mi == null) continue;
                for (Call callee : mi.callees) {
                    if (HELPERS.contains(callee.owner)) return true;
                    if (callee.owner.startsWith(ROOT)) queue.add(new Object[]{callee, depth + 1});
                }
            }
        }
        return false;
    }

    /** Declaring class up the hierarchy, plus implementations when the owner is abstract. */
    private static List<Call> resolve(Call call) {
        List<Call> out = new ArrayList<>();
        String k = call.name + call.desc;
        String cur = call.owner;
        while (cur != null && CLASSES.containsKey(cur)) {
            ClassInfo ci = CLASSES.get(cur);
            if (ci.methods.containsKey(k)) {
                out.add(new Call(cur, call.name, call.desc));
                break;
            }
            cur = ci.superName;
        }
        ClassInfo owner = CLASSES.get(call.owner);
        if (owner != null && (owner.isInterface || owner.isAbstract)) {
            for (ClassInfo ci : CLASSES.values()) {
                if ((ci.interfaces.contains(call.owner) || call.owner.equals(ci.superName)) && ci.methods.containsKey(k)) {
                    out.add(new Call(ci.name, call.name, call.desc));
                }
            }
        }
        return out;
    }

    private record Call(String owner, String name, String desc) {
        String key() { return owner + "." + name + desc; }
    }

    private static final class MethodInfo {
        final Set<Call> callees = new LinkedHashSet<>();
    }

    private static final class ClassInfo extends ClassVisitor {
        String name;
        String superName;
        Set<String> interfaces = Set.of();
        boolean isInterface;
        boolean isAbstract;
        final Map<String, MethodInfo> methods = new HashMap<>();

        ClassInfo() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] ifaces) {
            this.name = name;
            this.superName = superName;
            this.interfaces = ifaces == null ? Set.of() : Set.of(ifaces);
            this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            this.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
        }

        @Override
        public MethodVisitor visitMethod(int access, String mName, String desc, String sig, String[] ex) {
            MethodInfo info = new MethodInfo();
            methods.put(mName + desc, info);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                    info.callees.add(new Call(owner, n, d));
                }

                @Override
                public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                    // Lambdas and method references: the implementation handle is a bootstrap argument.
                    for (Object a : args) {
                        if (a instanceof Handle h) info.callees.add(new Call(h.getOwner(), h.getName(), h.getDesc()));
                    }
                }
            };
        }
    }
}

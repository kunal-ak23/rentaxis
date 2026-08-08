"use client";

import { motion, useInView } from "framer-motion";
import { ArrowRight, Building2, FileText, CreditCard, BarChart3, Users, Shield, Check, ChevronRight } from "lucide-react";
import { Link } from "@/i18n/routing";
import { useRef } from "react";

function AnimateIn({ children, className, delay = 0 }: { children: React.ReactNode; className?: string; delay?: number }) {
    const ref = useRef(null);
    const inView = useInView(ref, { once: true, margin: "-60px" });
    return (
        <motion.div
            ref={ref}
            initial={{ opacity: 0, y: 24 }}
            animate={inView ? { opacity: 1, y: 0 } : {}}
            transition={{ duration: 0.5, delay, ease: "easeOut" }}
            className={className}
        >
            {children}
        </motion.div>
    );
}

const features = [
    {
        icon: Building2,
        title: "Property Management",
        description: "Manage buildings, units, and vacancies across your entire portfolio from one dashboard.",
    },
    {
        icon: FileText,
        title: "Lease Lifecycle",
        description: "Draft, activate, and terminate leases with automated bilingual contract generation.",
    },
    {
        icon: CreditCard,
        title: "Payment Tracking",
        description: "Track cheques, online payments, and deposits with a complete payment schedule per lease.",
    },
    {
        icon: BarChart3,
        title: "Financial Reports",
        description: "Income statements, balance sheets, and NOI reports at org, property, or unit level.",
    },
    {
        icon: Users,
        title: "Renter Portal",
        description: "Tenants can view leases, accept contracts, and pay rent online through their own portal.",
    },
    {
        icon: Shield,
        title: "Multi-Tenant & Secure",
        description: "Each organisation gets isolated data. Role-based access, encrypted credentials, and audit trails.",
    },
];

const steps = [
    {
        step: "01",
        title: "Add Your Properties",
        description: "Import or create your buildings and units. Set types, sizes, and floor plans.",
    },
    {
        step: "02",
        title: "Draft & Sign Leases",
        description: "Create bilingual contracts, send to tenants for acceptance, and activate with one click.",
    },
    {
        step: "03",
        title: "Collect & Report",
        description: "Track every payment, generate financial statements, and monitor portfolio performance.",
    },
];

const stats = [
    { value: "100%", label: "UAE Compliant" },
    { value: "AR/EN", label: "Bilingual Contracts" },
    { value: "Real-time", label: "Financial Reports" },
    { value: "Ejari", label: "Integration Ready" },
];

const pricingPlans = [
    {
        name: "Starter",
        price: "Free",
        period: "",
        description: "For individual landlords getting started.",
        features: ["Up to 5 units", "Lease management", "Contract generation", "Basic reports", "Email support"],
        cta: "Get Started",
        highlighted: false,
    },
    {
        name: "Professional",
        price: "AED 299",
        period: "/month",
        description: "For growing property management businesses.",
        features: [
            "Up to 100 units",
            "Everything in Starter",
            "Online rent collection",
            "Renter portal",
            "Financial reports",
            "Priority support",
        ],
        cta: "Start Free Trial",
        highlighted: true,
    },
    {
        name: "Enterprise",
        price: "Custom",
        period: "",
        description: "For large portfolios with advanced needs.",
        features: [
            "Unlimited units",
            "Everything in Professional",
            "Multi-organisation",
            "API access",
            "Custom integrations",
            "Dedicated account manager",
        ],
        cta: "Contact Sales",
        highlighted: false,
    },
];

export default function MvpHero() {
    return (
        <>
            {/* ─── HERO ─── */}
            <section className="relative min-h-screen pt-32 pb-16 overflow-hidden bg-white">
                <div className="absolute inset-0 pointer-events-none">
                    <div className="absolute top-[-5%] left-[-5%] w-[35%] h-[35%] bg-primary/5 rounded-full blur-[100px] opacity-40" />
                    <div className="absolute bottom-[-5%] right-[-5%] w-[35%] h-[35%] bg-primary/10 rounded-full blur-[100px] opacity-40" />
                </div>

                <div className="container relative z-10 mx-auto px-4 md:px-6">
                    <div className="max-w-3xl mx-auto text-center">
                        <motion.div
                            initial={{ opacity: 0, y: 15 }}
                            animate={{ opacity: 1, y: 0 }}
                            transition={{ duration: 0.5 }}
                        >
                            <span className="inline-block px-3 py-1 mb-5 text-[10px] font-bold tracking-[0.2em] text-primary uppercase bg-primary/5 rounded-full border border-primary/10">
                                Built for the UAE Market
                            </span>
                            <h1 className="mb-6 text-3xl font-black tracking-tight text-foreground md:text-5xl lg:text-[3.5rem] leading-[1.1]">
                                Property management,<br />
                                <span className="text-primary">
                                    simplified.
                                </span>
                            </h1>
                            <p className="mb-10 text-base text-gray-500 md:text-lg max-w-xl mx-auto leading-relaxed font-medium">
                                The complete cloud platform for UAE landlords and property managers.
                                Leases, payments, contracts, and financials &mdash; all in one place.
                            </p>

                            <div className="flex flex-col items-center justify-center gap-4 sm:flex-row">
                                <Link
                                    href="/dashboard/properties"
                                    className="group flex items-center justify-center px-8 py-3.5 text-[11px] font-black uppercase tracking-widest text-primary-foreground transition-all duration-200 bg-primary rounded-full hover:opacity-90 shadow-xl shadow-primary/20 active:scale-95 cursor-pointer"
                                >
                                    Start for Free
                                    <ArrowRight className="ml-2 h-3.5 w-3.5 transition-transform group-hover:translate-x-1" />
                                </Link>
                                <a
                                    href="#features"
                                    className="px-8 py-3.5 text-[11px] font-black uppercase tracking-widest text-gray-500 transition-all duration-200 bg-white border border-border rounded-full hover:bg-gray-50 hover:text-foreground active:scale-95 cursor-pointer"
                                >
                                    See Features
                                </a>
                            </div>
                        </motion.div>

                        {/* Dashboard Preview */}
                        <motion.div
                            initial={{ opacity: 0, scale: 0.95, y: 30 }}
                            animate={{ opacity: 1, scale: 1, y: 0 }}
                            transition={{ duration: 0.8, delay: 0.2 }}
                            className="relative mt-16 max-w-5xl mx-auto"
                        >
                            <div className="relative p-1.5 bg-white border border-gray-100 rounded-[2rem] shadow-[0_20px_50px_rgba(0,0,0,0.05)] md:p-3 overflow-hidden">
                                <div className="overflow-hidden bg-gray-50 border border-gray-100 rounded-[1.5rem] h-[300px] md:h-[500px] flex items-center justify-center relative">
                                    <div className="w-full h-full p-6 md:p-8 flex flex-col gap-4">
                                        {/* Window dots */}
                                        <div className="flex gap-1.5">
                                            <div className="w-2 h-2 rounded-full bg-red-400/60" />
                                            <div className="w-2 h-2 rounded-full bg-amber-400/60" />
                                            <div className="w-2 h-2 rounded-full bg-emerald-400/60" />
                                        </div>
                                        {/* Sidebar + content */}
                                        <div className="flex-1 flex gap-4 mt-2">
                                            <div className="hidden md:flex w-48 flex-col gap-2">
                                                {["Dashboard", "Properties", "Leases", "Payments", "Finance"].map((item, i) => (
                                                    <div key={item} className={`h-8 rounded-lg flex items-center px-3 text-[10px] font-bold tracking-wide ${i === 0 ? "bg-primary/10 text-primary" : "text-gray-400"}`}>
                                                        {item}
                                                    </div>
                                                ))}
                                            </div>
                                            <div className="flex-1 flex flex-col gap-3">
                                                <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                                                    {[
                                                        { label: "Properties", value: "12" },
                                                        { label: "Occupancy", value: "94%" },
                                                        { label: "Revenue", value: "AED 1.2M" },
                                                        { label: "Overdue", value: "AED 0" },
                                                    ].map((card) => (
                                                        <div key={card.label} className="bg-white/80 rounded-xl border border-gray-100/50 p-3">
                                                            <div className="text-[9px] text-gray-400 font-bold uppercase tracking-wider">{card.label}</div>
                                                            <div className="text-sm font-black text-foreground mt-1">{card.value}</div>
                                                        </div>
                                                    ))}
                                                </div>
                                                <div className="flex-1 bg-white/80 rounded-xl border border-gray-100/50" />
                                            </div>
                                        </div>
                                    </div>
                                </div>

                                {/* Floating badge left */}
                                <motion.div
                                    animate={{ y: [0, -8, 0] }}
                                    transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }}
                                    className="absolute top-10 -left-4 md:-left-8 p-3 bg-white rounded-xl shadow-[0_8px_30px_rgb(0,0,0,0.04)] border border-gray-50 flex items-center gap-2.5"
                                >
                                    <div className="w-8 h-8 bg-emerald-50 text-emerald-600 rounded-lg flex items-center justify-center">
                                        <Check size={14} strokeWidth={3} />
                                    </div>
                                    <div className="text-left">
                                        <div className="text-[9px] text-gray-400 font-bold uppercase tracking-wider">Lease</div>
                                        <div className="text-xs font-bold text-foreground">Activated</div>
                                    </div>
                                </motion.div>

                                {/* Floating badge right */}
                                <motion.div
                                    animate={{ y: [0, 8, 0] }}
                                    transition={{ duration: 5, repeat: Infinity, ease: "easeInOut", delay: 1 }}
                                    className="absolute bottom-10 -right-4 md:-right-8 p-3 bg-white rounded-xl shadow-[0_8px_30px_rgb(0,0,0,0.04)] border border-gray-50 flex items-center gap-2.5"
                                >
                                    <div className="w-8 h-8 bg-primary/5 text-primary rounded-lg flex items-center justify-center">
                                        <CreditCard size={14} strokeWidth={2.5} />
                                    </div>
                                    <div className="text-left">
                                        <div className="text-[9px] text-gray-400 font-bold uppercase tracking-wider">Collected</div>
                                        <div className="text-xs font-bold text-foreground">AED 85K</div>
                                    </div>
                                </motion.div>
                            </div>
                        </motion.div>
                    </div>
                </div>
            </section>

            {/* ─── STATS BAR ─── */}
            <section className="py-12 bg-gray-50/50 border-y border-gray-100">
                <div className="container mx-auto px-4 md:px-6">
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-8 max-w-4xl mx-auto">
                        {stats.map((stat, i) => (
                            <AnimateIn key={stat.label} delay={i * 0.1} className="text-center">
                                <div className="text-2xl md:text-3xl font-black text-foreground tracking-tight">{stat.value}</div>
                                <div className="text-[11px] font-bold text-gray-400 uppercase tracking-wider mt-1">{stat.label}</div>
                            </AnimateIn>
                        ))}
                    </div>
                </div>
            </section>

            {/* ─── FEATURES ─── */}
            <section id="features" className="py-24 bg-white">
                <div className="container mx-auto px-4 md:px-6">
                    <AnimateIn className="text-center max-w-2xl mx-auto mb-16">
                        <span className="inline-block px-3 py-1 mb-4 text-[10px] font-bold tracking-[0.2em] text-primary uppercase bg-primary/5 rounded-full border border-primary/10">
                            Features
                        </span>
                        <h2 className="text-2xl md:text-4xl font-black tracking-tight text-foreground leading-tight">
                            Everything you need to manage<br className="hidden md:block" /> your properties
                        </h2>
                        <p className="mt-4 text-gray-500 font-medium max-w-lg mx-auto">
                            From lease drafting to financial reporting, RentAxis covers the full property management lifecycle.
                        </p>
                    </AnimateIn>

                    <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6 max-w-5xl mx-auto">
                        {features.map((feature, i) => (
                            <AnimateIn key={feature.title} delay={i * 0.08}>
                                <div className="group p-6 bg-white rounded-2xl border border-gray-100 hover:border-primary/20 hover:shadow-lg hover:shadow-primary/5 transition-all duration-300 h-full cursor-default">
                                    <div className="w-10 h-10 bg-primary/5 rounded-xl flex items-center justify-center mb-4 group-hover:bg-primary/10 transition-colors">
                                        <feature.icon size={20} className="text-primary" strokeWidth={2} />
                                    </div>
                                    <h3 className="text-sm font-black text-foreground tracking-tight mb-2">{feature.title}</h3>
                                    <p className="text-sm text-gray-500 leading-relaxed font-medium">{feature.description}</p>
                                </div>
                            </AnimateIn>
                        ))}
                    </div>
                </div>
            </section>

            {/* ─── HOW IT WORKS ─── */}
            <section id="how-it-works" className="py-24 bg-gray-50/50">
                <div className="container mx-auto px-4 md:px-6">
                    <AnimateIn className="text-center max-w-2xl mx-auto mb-16">
                        <span className="inline-block px-3 py-1 mb-4 text-[10px] font-bold tracking-[0.2em] text-primary uppercase bg-primary/5 rounded-full border border-primary/10">
                            How It Works
                        </span>
                        <h2 className="text-2xl md:text-4xl font-black tracking-tight text-foreground leading-tight">
                            Get started in minutes
                        </h2>
                        <p className="mt-4 text-gray-500 font-medium max-w-lg mx-auto">
                            Three simple steps to digitise your entire property portfolio.
                        </p>
                    </AnimateIn>

                    <div className="grid md:grid-cols-3 gap-8 max-w-4xl mx-auto">
                        {steps.map((step, i) => (
                            <AnimateIn key={step.step} delay={i * 0.15}>
                                <div className="relative text-center md:text-left">
                                    <div className="text-5xl font-black text-primary/10 mb-4">{step.step}</div>
                                    <h3 className="text-base font-black text-foreground tracking-tight mb-2">{step.title}</h3>
                                    <p className="text-sm text-gray-500 leading-relaxed font-medium">{step.description}</p>
                                    {i < steps.length - 1 && (
                                        <div className="hidden md:block absolute top-8 -right-4 text-primary/20">
                                            <ChevronRight size={24} />
                                        </div>
                                    )}
                                </div>
                            </AnimateIn>
                        ))}
                    </div>
                </div>
            </section>

            {/* ─── FEATURE SHOWCASE ─── */}
            <section className="py-24 bg-white">
                <div className="container mx-auto px-4 md:px-6 max-w-5xl">
                    {/* Bilingual Contracts */}
                    <AnimateIn>
                        <div className="flex flex-col md:flex-row items-center gap-12 mb-24">
                            <div className="flex-1">
                                <span className="inline-block px-3 py-1 mb-4 text-[10px] font-bold tracking-[0.2em] text-primary uppercase bg-primary/5 rounded-full border border-primary/10">
                                    Contracts
                                </span>
                                <h2 className="text-2xl md:text-3xl font-black tracking-tight text-foreground leading-tight mb-4">
                                    Bilingual contracts,<br />generated instantly
                                </h2>
                                <p className="text-gray-500 font-medium leading-relaxed mb-6">
                                    Every lease generates a professional Arabic/English PDF contract with all terms, payment schedules, and Ejari details. Ready for signing in seconds.
                                </p>
                                <Link
                                    href="/dashboard/properties"
                                    className="inline-flex items-center text-sm font-bold text-primary hover:underline cursor-pointer group"
                                >
                                    Try it now
                                    <ArrowRight size={14} className="ml-1 group-hover:translate-x-1 transition-transform" />
                                </Link>
                            </div>
                            <div className="flex-1 w-full">
                                <div className="bg-gray-50 rounded-2xl border border-gray-100 p-6 md:p-8">
                                    <div className="space-y-3">
                                        <div className="flex justify-between items-center">
                                            <span className="text-xs font-bold text-foreground">TENANCY CONTRACT</span>
                                            <span className="text-xs font-bold text-gray-400" dir="rtl">عقد إيجار</span>
                                        </div>
                                        <div className="h-[1px] bg-primary/20" />
                                        {["Landlord / المؤجر", "Tenant / المستأجر", "Annual Rent / الإيجار السنوي", "Duration / المدة"].map((row) => (
                                            <div key={row} className="flex justify-between py-2 border-b border-gray-100 last:border-0">
                                                <span className="text-[11px] text-gray-500 font-medium">{row}</span>
                                                <div className="w-24 h-3 bg-gray-200/60 rounded" />
                                            </div>
                                        ))}
                                        <div className="flex justify-between pt-4">
                                            <div className="text-center">
                                                <div className="w-20 h-[1px] bg-gray-300 mb-1" />
                                                <span className="text-[9px] text-gray-400">Landlord</span>
                                            </div>
                                            <div className="text-center">
                                                <div className="w-20 h-[1px] bg-gray-300 mb-1" />
                                                <span className="text-[9px] text-gray-400">Tenant</span>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </AnimateIn>

                    {/* Payment Tracking */}
                    <AnimateIn>
                        <div className="flex flex-col md:flex-row-reverse items-center gap-12">
                            <div className="flex-1">
                                <span className="inline-block px-3 py-1 mb-4 text-[10px] font-bold tracking-[0.2em] text-primary uppercase bg-primary/5 rounded-full border border-primary/10">
                                    Payments
                                </span>
                                <h2 className="text-2xl md:text-3xl font-black tracking-tight text-foreground leading-tight mb-4">
                                    Every cheque tracked,<br />every payment recorded
                                </h2>
                                <p className="text-gray-500 font-medium leading-relaxed mb-6">
                                    From post-dated cheques to online transfers, track every installment through its lifecycle: collected, deposited, cleared, or bounced.
                                </p>
                                <Link
                                    href="/dashboard/properties"
                                    className="inline-flex items-center text-sm font-bold text-primary hover:underline cursor-pointer group"
                                >
                                    Learn more
                                    <ArrowRight size={14} className="ml-1 group-hover:translate-x-1 transition-transform" />
                                </Link>
                            </div>
                            <div className="flex-1 w-full">
                                <div className="bg-gray-50 rounded-2xl border border-gray-100 p-6 md:p-8">
                                    <div className="space-y-3">
                                        {[
                                            { label: "Installment 1", status: "Cleared", color: "bg-emerald-100 text-emerald-700" },
                                            { label: "Installment 2", status: "Deposited", color: "bg-blue-100 text-blue-700" },
                                            { label: "Installment 3", status: "Collected", color: "bg-amber-100 text-amber-700" },
                                            { label: "Installment 4", status: "Pending", color: "bg-gray-100 text-gray-500" },
                                        ].map((row) => (
                                            <div key={row.label} className="flex items-center justify-between py-2.5 border-b border-gray-100 last:border-0">
                                                <div>
                                                    <div className="text-xs font-bold text-foreground">{row.label}</div>
                                                    <div className="text-[10px] text-gray-400 mt-0.5">AED 25,000</div>
                                                </div>
                                                <span className={`px-2.5 py-1 text-[10px] font-bold rounded-full ${row.color}`}>
                                                    {row.status}
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>
                        </div>
                    </AnimateIn>
                </div>
            </section>

            {/* ─── PRICING ─── */}
            <section id="pricing" className="py-24 bg-gray-50/50">
                <div className="container mx-auto px-4 md:px-6">
                    <AnimateIn className="text-center max-w-2xl mx-auto mb-16">
                        <span className="inline-block px-3 py-1 mb-4 text-[10px] font-bold tracking-[0.2em] text-primary uppercase bg-primary/5 rounded-full border border-primary/10">
                            Pricing
                        </span>
                        <h2 className="text-2xl md:text-4xl font-black tracking-tight text-foreground leading-tight">
                            Simple, transparent pricing
                        </h2>
                        <p className="mt-4 text-gray-500 font-medium max-w-lg mx-auto">
                            Start free and scale as your portfolio grows. No hidden fees.
                        </p>
                    </AnimateIn>

                    <div className="grid md:grid-cols-3 gap-6 max-w-5xl mx-auto">
                        {pricingPlans.map((plan, i) => (
                            <AnimateIn key={plan.name} delay={i * 0.1}>
                                <div
                                    className={`relative p-6 rounded-2xl border h-full flex flex-col ${
                                        plan.highlighted
                                            ? "bg-white border-primary/30 shadow-xl shadow-primary/5 ring-1 ring-primary/10"
                                            : "bg-white border-gray-100 hover:border-gray-200"
                                    } transition-all duration-300`}
                                >
                                    {plan.highlighted && (
                                        <div className="absolute -top-3 left-1/2 -translate-x-1/2 px-3 py-1 text-[9px] font-bold uppercase tracking-widest text-primary-foreground bg-primary rounded-full">
                                            Most Popular
                                        </div>
                                    )}
                                    <div className="mb-6">
                                        <h3 className="text-sm font-black text-foreground tracking-tight mb-1">{plan.name}</h3>
                                        <p className="text-xs text-gray-400 font-medium mb-4">{plan.description}</p>
                                        <div className="flex items-baseline gap-1">
                                            <span className="text-3xl font-black text-foreground">{plan.price}</span>
                                            {plan.period && <span className="text-sm text-gray-400 font-medium">{plan.period}</span>}
                                        </div>
                                    </div>
                                    <ul className="space-y-3 mb-8 flex-1">
                                        {plan.features.map((feature) => (
                                            <li key={feature} className="flex items-start gap-2.5">
                                                <Check size={14} className="text-primary mt-0.5 flex-shrink-0" strokeWidth={3} />
                                                <span className="text-sm text-gray-600 font-medium">{feature}</span>
                                            </li>
                                        ))}
                                    </ul>
                                    <Link
                                        href="/dashboard/properties"
                                        className={`block text-center px-6 py-3 text-[11px] font-bold uppercase tracking-widest rounded-full transition-all duration-200 active:scale-95 cursor-pointer ${
                                            plan.highlighted
                                                ? "bg-primary text-primary-foreground hover:opacity-90 shadow-lg shadow-primary/20"
                                                : "bg-gray-100 text-foreground hover:bg-gray-200"
                                        }`}
                                    >
                                        {plan.cta}
                                    </Link>
                                </div>
                            </AnimateIn>
                        ))}
                    </div>
                </div>
            </section>

            {/* ─── CTA ─── */}
            <section className="py-24 bg-white">
                <div className="container mx-auto px-4 md:px-6">
                    <AnimateIn>
                        <div className="max-w-3xl mx-auto text-center bg-gradient-to-br from-primary/5 to-primary/10 rounded-3xl p-12 md:p-16 border border-primary/10">
                            <h2 className="text-2xl md:text-4xl font-black tracking-tight text-foreground leading-tight mb-4">
                                Ready to simplify your<br />property management?
                            </h2>
                            <p className="text-gray-500 font-medium max-w-md mx-auto mb-8">
                                Join landlords across the UAE who are saving hours every week with RentAxis.
                            </p>
                            <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
                                <Link
                                    href="/dashboard/properties"
                                    className="group flex items-center justify-center px-8 py-3.5 text-[11px] font-black uppercase tracking-widest text-primary-foreground transition-all duration-200 bg-primary rounded-full hover:opacity-90 shadow-xl shadow-primary/20 active:scale-95 cursor-pointer"
                                >
                                    Get Started for Free
                                    <ArrowRight className="ml-2 h-3.5 w-3.5 transition-transform group-hover:translate-x-1" />
                                </Link>
                                <a
                                    href="#features"
                                    className="text-sm font-bold text-primary hover:underline cursor-pointer"
                                >
                                    Learn more
                                </a>
                            </div>
                        </div>
                    </AnimateIn>
                </div>
            </section>

            {/* ─── FOOTER ─── */}
            <footer className="py-12 bg-gray-50/50 border-t border-gray-100">
                <div className="container mx-auto px-4 md:px-6">
                    <div className="grid md:grid-cols-4 gap-8 max-w-5xl mx-auto">
                        <div className="md:col-span-1">
                            <div className="flex items-center gap-2 mb-4">
                                <div className="w-7 h-7 bg-primary rounded-lg flex items-center justify-center text-primary-foreground font-black text-[10px] shadow-md shadow-primary/20">
                                    R
                                </div>
                                <span className="text-sm font-black text-foreground tracking-tight">RentAxis</span>
                            </div>
                            <p className="text-xs text-gray-400 font-medium leading-relaxed">
                                Enterprise lease management platform built for the UAE market.
                            </p>
                        </div>
                        <div>
                            <h4 className="text-[10px] font-bold uppercase tracking-widest text-gray-400 mb-4">Product</h4>
                            <ul className="space-y-2.5">
                                {["Features", "Pricing", "Security", "Roadmap"].map((item) => (
                                    <li key={item}>
                                        <a href="#" className="text-xs text-gray-500 hover:text-primary font-medium transition-colors cursor-pointer">{item}</a>
                                    </li>
                                ))}
                            </ul>
                        </div>
                        <div>
                            <h4 className="text-[10px] font-bold uppercase tracking-widest text-gray-400 mb-4">Company</h4>
                            <ul className="space-y-2.5">
                                {["About", "Blog", "Careers", "Contact"].map((item) => (
                                    <li key={item}>
                                        <a href="#" className="text-xs text-gray-500 hover:text-primary font-medium transition-colors cursor-pointer">{item}</a>
                                    </li>
                                ))}
                            </ul>
                        </div>
                        <div>
                            <h4 className="text-[10px] font-bold uppercase tracking-widest text-gray-400 mb-4">Legal</h4>
                            <ul className="space-y-2.5">
                                <li>
                                    <Link href="/privacy" className="text-xs text-gray-500 hover:text-primary font-medium transition-colors cursor-pointer">
                                        Privacy Policy
                                    </Link>
                                </li>
                                <li>
                                    <Link href="/terms" className="text-xs text-gray-500 hover:text-primary font-medium transition-colors cursor-pointer">
                                        Terms of Service
                                    </Link>
                                </li>
                            </ul>
                        </div>
                    </div>
                    <div className="mt-12 pt-6 border-t border-gray-100 text-center">
                        <p className="text-[11px] text-gray-400 font-medium">
                            &copy; 2024 RentAxis. All rights reserved.
                        </p>
                    </div>
                </div>
            </footer>
        </>
    );
}

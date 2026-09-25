// src/components/nav/NavShellContext.tsx
"use client";
import { createContext, useContext, useState } from "react";

type NavShell = { drawerOpen: boolean; setDrawerOpen: (open: boolean) => void };
const Ctx = createContext<NavShell>({ drawerOpen: false, setDrawerOpen: () => {} });

export function NavShellProvider({ children }: { children: React.ReactNode }) {
    const [drawerOpen, setDrawerOpen] = useState(false);
    return <Ctx.Provider value={{ drawerOpen, setDrawerOpen }}>{children}</Ctx.Provider>;
}
export const useNavShell = () => useContext(Ctx);

"use client";

import { forwardRef } from "react";
import { Link } from "@/i18n/routing";
import { useWorkspace } from "./WorkspaceContext";

type LinkProps = React.ComponentProps<typeof Link>;

/**
 * A link that opts into the workspace's opening rules (spec §3). `opens`:
 * - "screen": a menu item — focus the open tab on that path, else a new tab
 *   (a link that carries a query focuses only an exact match);
 * - "record": one contract, voucher, tenant — focus the tab showing it, else a
 *   new tab next to the current one.
 * Ctrl/Cmd-click, middle-click and "Open in new tab" stay the browser's. With
 * the workspace off it is the plain next-intl Link.
 */
const WorkspaceLink = forwardRef<HTMLAnchorElement, LinkProps & { href: string; opens: "screen" | "record" }>(
    function WorkspaceLink({ opens, onClick, href, ...rest }, ref) {
        const ws = useWorkspace();
        return (
            <Link
                ref={ref}
                href={href}
                {...rest}
                onClick={e => {
                    onClick?.(e);
                    if (e.defaultPrevented || !ws?.enabled) return;
                    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
                    e.preventDefault();
                    ws.open(href, opens === "record" ? "record" : href.includes("?") ? "query" : "menu");
                }}
            />
        );
    },
);

export default WorkspaceLink;

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import en from "../../../../../../messages/en.json";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const push = vi.fn();
vi.mock("@/i18n/routing", () => ({ useRouter: () => ({ push }) }));

import SetPasswordForm from "../SetPasswordForm";

afterEach(cleanup);

describe("SetPasswordForm", () => {
    it("speaks from the catalogue and says the password applies everywhere", () => {
        render(<SetPasswordForm token="t" email="p@x.com" name="Pat" locale="en" />);
        expect(screen.getByText(en.Auth.setTitle)).toBeTruthy();
        expect(screen.getByText(en.Auth.setIntro.replace("{name}", "Pat").replace("{email}", "p@x.com"))).toBeTruthy();
    });

    it("greets by address when the link names nobody (forgot-password)", () => {
        render(<SetPasswordForm token="t" email="p@x.com" name="" locale="en" />);
        expect(screen.getByText(en.Auth.setIntroNoName.replace("{email}", "p@x.com"))).toBeTruthy();
    });

    it("explains an expired link", async () => {
        global.fetch = vi.fn(async () => ({ status: 410 }) as Response) as unknown as typeof fetch;
        const { container } = render(<SetPasswordForm token="t" email="p@x.com" name="Pat" locale="en" />);
        const inputs = container.querySelectorAll("input");
        fireEvent.change(inputs[0], { target: { value: "long-enough-1" } });
        fireEvent.change(inputs[1], { target: { value: "long-enough-1" } });
        fireEvent.submit(container.querySelector("form")!);
        expect(await screen.findByText(en.Auth.errExpired)).toBeTruthy();
    });
});

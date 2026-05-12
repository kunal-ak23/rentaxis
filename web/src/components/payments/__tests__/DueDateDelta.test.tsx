import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import DueDateDelta from "../DueDateDelta";

describe("DueDateDelta", () => {
  it("renders nothing when chequeDate is null", () => {
    const { container } = render(<DueDateDelta dueDate="2026-06-05" chequeDate={null} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders 0d when same day", () => {
    render(<DueDateDelta dueDate="2026-06-05" chequeDate="2026-06-05" />);
    expect(screen.getByText("0d")).toBeInTheDocument();
  });

  it("renders −Nd in green when cheque is before due", () => {
    render(<DueDateDelta dueDate="2026-06-05" chequeDate="2026-06-02" />);
    const el = screen.getByText("−3d");
    expect(el.className).toMatch(/emerald|green/);
  });

  it("renders +Nd in red when cheque is after due", () => {
    render(<DueDateDelta dueDate="2026-06-05" chequeDate="2026-06-08" />);
    const el = screen.getByText("+3d");
    expect(el.className).toMatch(/red|rose/);
  });
});

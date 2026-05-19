"use client";
import { useSearchParams } from "next/navigation";
import RenewalIntentConfirm from "@/components/renewals/RenewalIntentConfirm";

export default function RenewalIntentPage() {
  const sp = useSearchParams();
  const token = sp.get("token") ?? "";
  const intent = sp.get("intent") ?? "";
  if (!token) return <p className="p-6">Invalid link</p>;
  return <RenewalIntentConfirm token={token} intent={intent} />;
}

# Miftah Resident app (`renter`)

The Flutter renter app. Shared theme, API client, providers and widgets live
in `../../packages/rentaxis_core`; this app holds the screens and router.

## Run

```bash
flutter run                                   # against prod
flutter run --dart-define=API_BASE_URL=http://localhost:8081/api   # local backend
flutter test                                  # widget and unit tests
```

Dev-only `--dart-define`s (all off / unset in a store build):

| Define | Effect |
|---|---|
| `API_BASE_URL` | Backend base URL, with `/api`. |
| `START_ROUTE` | Boot straight to a route, e.g. `/payments`. |
| `DEMO_PAYMENTS=true` | Pseudo online payments and rewards, see below. |

## Demo: online payments, cashback and vouchers

`--dart-define=DEMO_PAYMENTS=true` turns on an interactive, offline mock of
a payment gateway and a rewards wallet, for demos only. Nothing in it calls
the backend; the state lives in memory and resets when the app restarts.

```bash
flutter run --dart-define=API_BASE_URL=http://localhost:8081/api --dart-define=DEMO_PAYMENTS=true
```

What it adds, in the order a demo walks it:

1. **Home hero** shows **Pay now** while an instalment is owed.
2. **Wallet tab** opens with a Miftah Wallet strip (cashback balance, a
   Rewards button with a badge for unopened scratch cards) and a **Pay online**
   button on every row owed now.
3. **Checkout** (`/pay/:chequeId`) is a Razorpay-style hosted page: order
   summary with the convenience fee waived, a *Use wallet balance* switch that
   spends cashback first, then saved card, new card (number, expiry, CVV),
   Apple Pay on iOS or Google Pay elsewhere, and net banking with a bank
   picker. **Pay** walks three animated gateway steps and lands on a receipt.
4. **Receipt** records the payment locally: the row reads *Cleared · Online*
   on the Wallet tab and drops out of the home hero. It also hands over a
   **scratch card** (rub it, or tap *Reveal now*). Cards rotate cashback,
   voucher, cashback, better luck. Cashback is 1.5% (AED 5 to 100) and lands
   in the wallet at once; a voucher is saved with its code.
5. **Rewards** (`/payments/rewards`) has three tabs: scratch cards (unopened
   ones can be scratched from here), vouchers (copy the code, mark used) and
   the cashback history.

Code: `lib/demo/` (flag, in-memory wallet state, paid-row overlay) and
`lib/screens/wallet/` (checkout, scratch card, rewards, wallet strip). Tests:
`test/wallet_demo_test.dart`, `test/checkout_flow_test.dart`.

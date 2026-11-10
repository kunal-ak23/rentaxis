# Demo script: online payments, cashback and rewards (renter app)

Build: `flutter build apk --release --dart-define=DEMO_PAYMENTS=true` (or `flutter run` with the
same define). Everything in this flow is on-device make-believe: no gateway, no backend writes.
Restarting the app resets the wallet, the scratch cards and the "paid" rows.

## Before the room

1. Install the demo build and sign in as a renter who has at least two instalments **owed now**
   (overdue or due). Three is better: cashback, then voucher, then cashback again.
2. Open the Wallet tab once so the data is cached, then go back to Home.
3. Keep the phone on, brightness up, notifications silenced.

## The walk (about 4 minutes)

**1. Home (20 s).** "Here is Rajesh. His next instalment is due and the hero now says *Pay now*."
Tap **Pay now**. (If you are not on the hero, Wallet tab → any **Pay online** button.)

**2. Checkout (60 s).** This is a hosted-checkout shape like Razorpay or Stripe:
- Point at the order summary: instalment, due date, amount, convenience fee **waived**.
- Flip **Use wallet balance**: the header amount drops by the cashback balance. "Cashback he
  earned last month pays part of this month's rent."
- Scroll the methods: saved Visa, new card (number, expiry, CVV), Apple Pay or Google Pay, net
  banking with a bank picker. Pick one. Say: "Card details never touch Miftah; they go to the
  gateway. We just get the receipt."
- Tap **Pay**. Three steps animate: contacting the bank, authorising, confirming with Miftah.

**3. Receipt (30 s).** "Reference, method, wallet applied, date. The management side sees this
instalment as cleared online, no cheque to chase." Then the gold card: **You've earned a
scratch card**.

**4. Scratch (30 s).** Tap **Scratch**, then rub the card with your finger (or tap *Reveal now* if
you are short of time). First card is cashback: "AED 100, the cap, straight into his wallet."
Tap **Collect**, then **Done**.

**5. Wallet tab (30 s).** Show the three things that moved without a page reload:
- Miftah Wallet strip: balance went up.
- Due now: went down by one instalment; the paid row reads *Cleared · Online* with the reference.
- The hero on Home no longer shows that instalment.

**6. Rewards (45 s).** Tap **Rewards**:
- *Cards*: the one just scratched, plus history. Unopened cards show "Tap to scratch".
- *Vouchers*: partner vouchers with a code, **copy**, **Mark used**. "This is where the promotions
  module plugs in: the same businesses that advertise in the app can fund these."
- *History*: cashback in, wallet spent, payments out.

**7. Second payment (optional, 45 s).** Pay another instalment from the Wallet tab. The card this
time is a **voucher** (Palm Fitness). Scratch it and show it landing under *Vouchers*.

## Lines that land

- "Cashback is 1.5 percent, five to a hundred dirhams, and it is spendable on rent, not points."
- "One tap to pay, zero cheques to bank, and every instalment leaves a reference the accountant
  can match."
- "Vouchers are our partners' money, not the landlord's: a second revenue line for the property."

## If something goes wrong

- Nothing owed on the renter: use the Wallet tab's **Due now** filter on a different renter.
- Pay button greyed out on *New card*: it needs a 12+ digit number, 4-digit expiry and a 3-digit CVV.
- Want a clean slate: kill the app and reopen. The wallet starts again at AED 42.50 with one voucher.

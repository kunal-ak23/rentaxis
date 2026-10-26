// The renter app's Play Store review scenarios. Each one starts signed out on
// the login screen and ends wherever its story ends; the test signs out
// off-camera afterwards. Everything a scenario creates it also removes, where
// the app lets a renter do that (tickets cannot be deleted).
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
// ignore: depend_on_referenced_packages
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/router.dart';
import 'package:renter/screens/browse/browse_screen.dart';
import 'package:renter/screens/contracts_screen.dart';
import 'package:renter/screens/notifications_screen.dart';
import 'package:renter/widgets/ad_card.dart';
import 'package:renter/widgets/ticket_attachment_tile.dart';

import 'pickers.dart';
import 'reviewer.dart';

typedef Scenario = Future<void> Function(Reviewer r);

/// Scenario name → (account, script). The names are the recorder script's.
const scenarioAccounts = <String, String>{
  'sign-in-and-home': 'AHMED',
  'offers': 'AHMED',
  'payments-and-receipts': 'AHMED',
  'returned-cheque-and-penalties': 'FATIMA',
  'contract-acceptance': 'SARA',
  'renewal': 'RAJESH',
  'maintenance-ticket': 'AHMED',
  'facilities-and-parking': 'AHMED',
  'gate-pass': 'AHMED',
  'notifications': 'AHMED',
  'marketplace': 'AHMED',
  'profile-arabic-and-account': 'AHMED',
};

final scenarios = <String, Scenario>{
  'sign-in-and-home': _signInAndHome,
  'offers': _offers,
  'payments-and-receipts': _paymentsAndReceipts,
  'returned-cheque-and-penalties': _returnedChequeAndPenalties,
  'contract-acceptance': _contractAcceptance,
  'renewal': _renewal,
  'maintenance-ticket': _maintenanceTicket,
  'facilities-and-parking': _facilitiesAndParking,
  'gate-pass': _gatePass,
  'notifications': _notifications,
  'marketplace': _marketplace,
  'profile-arabic-and-account': _profileArabicAndAccount,
};

Future<void> _signInAndHome(Reviewer r) async {
  // Lease summary and next payment sit at the top of home.
  await r.waitFor(r.textCI('View cheques'), 'the lease and payment summary');
  await r.dwell(2);
  await r.reveal(find.byType(AdCard), 'the offers strip', alignment: 0.5);
  await r.dwell(2.5);
  await r.reveal(find.text('Maintain'), 'quick actions', alignment: 0.3);
  await r.dwell(1.5);
  await r.scroll(420, dwellAfter: 1.5);
  await r.scroll(420, dwellAfter: 1.5);
  await r.scroll(-1400, dwellAfter: 1.5);
}

Future<void> _offers(Reviewer r) async {
  await r.reveal(find.byType(AdCard), 'the offers strip', alignment: 0.5);
  await r.dwell(1.5);
  final seeAll = find.byKey(const Key('promo-offers-link'));
  final seeAllTile = find.byKey(const Key('promo-see-all'));
  if (r.has(seeAll)) {
    await r.tap(seeAll, 'the offers link');
  } else if (r.has(seeAllTile)) {
    await r.tap(seeAllTile, 'see all offers');
  } else {
    // With every live ad already in the home strip the app shows no "see all"
    // door, so this is the one screen reached by route.
    note(
      'no on-screen route to Offers (home shows every ad); opening by route',
    );
    r.container.read(routerProvider).push('/offers');
    await r.dwell(1);
  }
  await r.waitFor(find.byType(AdCard), 'the offers list');
  await r.dwell(2);
  final coupon = find.byWidgetPredicate(
    (w) => w is AdCard && w.ad.ctaType == PromoCtaType.coupon,
  );
  if (!r.has(coupon)) {
    note(
      'no coupon offer live; a website or phone offer would leave the app, '
      'so no ad is opened',
    );
    await r.dwell(2);
    return;
  }
  await r.tap(coupon, 'a coupon offer', dwellAfter: 2.5);
  final copy = find.byKey(const Key('coupon-copy'));
  if (r.has(copy)) await r.tap(copy, 'copy the coupon code', dwellAfter: 2);
  // Dismiss the sheet the way a person does: tap above it.
  await r.touch(const Offset(200, 140));
  await r.dwell(1.5);
}

Future<void> _paymentsAndReceipts(Reviewer r) async {
  await r.tap(r.navItem('Wallet'), 'the Wallet tab', dwellAfter: 2.5);
  await r.waitFor(find.byKey(const Key('wallet-due')), 'the wallet summary');
  await r.scroll(380, dwellAfter: 1.5);
  await r.tap(
    find.widgetWithText(ChoiceChip, 'Cleared'),
    'the Cleared filter',
    dwellAfter: 2,
  );
  // The receipt downloads into the system share sheet, which would take the
  // video out of the app: show the button, do not press it.
  await r.reveal(find.text('Receipt'), 'a cleared cheque receipt');
  await r.dwell(2.5);
  await r.scroll(300, dwellAfter: 1.5);
  await r.tap(
    find.widgetWithText(ChoiceChip, 'All'),
    'the All filter',
    dwellAfter: 1.5,
  );
}

Future<void> _returnedChequeAndPenalties(Reviewer r) async {
  await r.tap(r.navItem('Wallet'), 'the Wallet tab', dwellAfter: 2.5);
  await r.waitFor(find.byKey(const Key('wallet-due')), 'the wallet summary');
  // The cheque the bank returned and nobody has replaced yet: its card names
  // the return reason (the badge may read Overdue once it is past grace).
  await r.reveal(
    find.textContaining('Return reason'),
    'the returned cheque and its reason',
    alignment: 0.6,
  );
  await r.dwell(3);
  await r.scroll(-2000, dwellAfter: 1);
  await r.tap(find.text('Penalties & charges'), 'penalties', dwellAfter: 2.5);
  await r.tap(find.text('Cleared'), 'cleared penalties', dwellAfter: 2);
  await r.tap(find.text('Uncleared'), 'open penalties', dwellAfter: 1.5);
  await r.back();
}

Future<void> _contractAcceptance(Reviewer r) async {
  await r.tap(
    find.text('Contract'),
    'the Contract quick action',
    dwellAfter: 2.5,
  );
  await r.waitFor(find.byType(ContractsScreen), 'contracts');
  final accept = find.widgetWithText(FilledButton, 'Accept');
  if (!r.has(accept)) {
    await r.dwell(2);
    fail(
      'no contract is waiting for this renter\'s acceptance '
      '(it must be PENDING_SIGNATURE with a generated contract document)',
    );
  }
  await r.reveal(find.text('View / save contract'), 'the contract document');
  await r.tap(
    find.text('View / save contract'),
    'open the contract',
    dwellAfter: 2,
  );
  await r.waitUntil(
    () => r.tester.widget<FilledButton>(accept.first).onPressed != null,
    'Accept to enable once the contract was opened',
  );
  await r.tap(accept, 'Accept', dwellAfter: 2.5);
  await r.waitFor(find.text('Accept this contract?'), 'the accept dialog');
  // Never accepted here: the dialog is shown, then cancelled.
  await r.tap(find.text('Cancel'), 'cancel the acceptance', dwellAfter: 2);
}

Future<void> _renewal(Reviewer r) async {
  await r.tap(r.navItem('Services'), 'the Services tab', dwellAfter: 2);
  await r.tap(find.text('Renewals'), 'Renewals', dwellAfter: 2.5);
  await r.waitFor(find.text('Contract renewals'), 'renewals');
  final discuss = find.widgetWithText(OutlinedButton, 'Discuss');
  if (!r.has(discuss)) {
    note('no renewal window is open for this renter; showing the screen only');
    await r.dwell(3);
    return;
  }
  await r.reveal(discuss, 'the renewal choices');
  await r.dwell(1.5);
  await r.tap(discuss, 'Discuss', dwellAfter: 3);
}

Future<void> _maintenanceTicket(Reviewer r) async {
  final photo = await drawLeakPhoto('kitchen-sink-leak');
  final closeUp = await drawLeakPhoto(
    'kitchen-sink-leak-close-up',
    closeUp: true,
  );
  ImagePickerPlatform.instance = FakeGallery([photo]);
  FilePicker.platform = FakeFileBrowser(closeUp);

  await r.tap(
    find.text('Maintain'),
    'the Maintain quick action',
    dwellAfter: 2,
  );
  await r.tap(find.text('+ NEW'), 'new request', dwellAfter: 2);
  await r.waitFor(find.text('Create Ticket'), 'the ticket form');
  await r.type(
    find.widgetWithText(TextFormField, 'Title'),
    'Kitchen sink is leaking',
    'the title',
  );
  r.hideKeyboard();
  await r.tap(find.text('Plumbing'), 'the Plumbing category');
  await r.reveal(
    find.widgetWithText(TextFormField, 'Description'),
    'the description',
  );
  await r.type(
    find.widgetWithText(TextFormField, 'Description'),
    'Water drips from the pipe under the sink and pools in the cabinet.',
    'the description',
  );
  r.hideKeyboard();
  await r.reveal(find.text('Gallery'), 'the attachment buttons');
  await r.tap(find.text('Gallery'), 'attach a photo', dwellAfter: 2);
  await r.reveal(find.text('Submit Ticket'), 'submit');
  await r.tap(find.text('Submit Ticket'), 'submit the ticket', dwellAfter: 1);
  // Submitting replaces the form with the new ticket's detail.
  await r.waitFor(find.text('+ ADD'), 'the new ticket', seconds: 45);
  await r.dwell(2.5);
  await r.reveal(find.text('+ ADD'), 'the photos row');
  await r.tap(find.text('+ ADD'), 'add an attachment', dwellAfter: 1);
  await r.waitUntil(
    () => find.byType(TicketAttachmentTile).evaluate().length >= 2,
    'both attachments on the ticket',
    seconds: 45,
  );
  await r.dwell(1.5);
  // Let the "ticket created" message go first, so a snackbar below means
  // the photo itself failed.
  await r.waitUntil(() => !r.has(find.byType(SnackBar)), 'messages to clear');
  await r.tap(
    find.byType(TicketAttachmentTile),
    'open the photo',
    dwellAfter: 1,
  );
  final viewer = find.text('Close');
  final failed = find.byType(SnackBar);
  await r.waitUntil(() => r.has(viewer) || r.has(failed), 'the photo viewer');
  if (r.has(failed)) {
    // The local stack keeps attachments in Azurite, whose URLs the backend's
    // download does not read (it reads Azure Blob URLs only).
    note('the photo could not be downloaded for viewing');
    await r.dwell(2);
    return;
  }
  await r.dwell(2.5);
  await r.tap(viewer, 'close the photo', dwellAfter: 1.5);
}

Future<void> _facilitiesAndParking(Reviewer r) async {
  await r.tap(r.navItem('Services'), 'the Services tab', dwellAfter: 2);
  await r.tap(find.text('Facilities'), 'Facilities', dwellAfter: 2.5);
  await r.waitFor(r.textCI('Amenities'), 'the amenities');
  await r.dwell(1.5);

  final freeAmenity = find.ancestor(
    of: find.text('Free'),
    matching: find.byWidgetPredicate(
      (w) => w.runtimeType.toString() == '_AmenityCard',
    ),
  );
  await r.reveal(freeAmenity, 'a free amenity');
  final request = find.descendant(
    of: freeAmenity,
    matching: r.textCI('Request'),
  );
  if (!r.has(request)) {
    fail(
      'the free amenity offers no Request button — an earlier run may have '
      'left a pending request; cancel it under My requests',
    );
  }
  await r.tap(request, 'request the free amenity', dwellAfter: 2);
  final submit = find.byKey(const Key('booking-submit'));
  await r.waitFor(submit, 'the booking sheet');
  await r.reveal(submit, 'send request');
  await r.tap(submit, 'send the request', dwellAfter: 2.5);
  await r.waitFor(find.text('Your request was sent'), 'the request to go in');
  await r.dwell(1.5);

  await r.tap(
    find.byKey(const Key('my-requests')),
    'My requests',
    dwellAfter: 2.5,
  );
  final cancel = find.byWidgetPredicate(
    (w) =>
        w.key is ValueKey<String> &&
        (w.key! as ValueKey<String>).value.startsWith('cancel-'),
  );
  await r.reveal(cancel, 'the new request');
  await r.dwell(1.5);
  await r.tap(cancel, 'cancel the request', dwellAfter: 1.5);
  await r.tap(find.text('Confirm'), 'confirm the cancellation', dwellAfter: 1);
  await r.waitFor(find.text('Request cancelled'), 'the cancellation');
  await r.dwell(2);
  await r.back();

  await r.reveal(r.textCI('Parking'), 'parking', alignment: 0.2);
  await r.dwell(3);
}

Future<void> _gatePass(Reviewer r) async {
  await r.tap(r.navItem('Pass'), 'the Pass button', dwellAfter: 2);
  await r.tap(find.byTooltip('New pass'), 'new pass', dwellAfter: 2);
  await r.waitFor(find.text('New Gate Pass'), 'the pass form');
  await r.type(
    find.widgetWithText(TextFormField, 'Who is visiting?'),
    'Omar Haddad',
    'the guest name',
  );
  await r.type(
    find.widgetWithText(TextFormField, '+971 50 123 4567'),
    '+971 50 555 0147',
    'the guest phone',
  );
  await r.type(
    find.widgetWithText(TextFormField, 'Delivery, family visit, maintenance…'),
    'Family dinner',
    'the purpose',
  );
  r.hideKeyboard();
  await r.reveal(find.text('Pick a date'), 'the visit date');
  await r.tap(find.text('Pick a date'), 'pick the visit date', dwellAfter: 1.5);
  // Tomorrow, so the default 09:00–18:00 window is never already over.
  final now = DateTime.now();
  final tomorrow = DateTime(now.year, now.month, now.day + 1);
  if (tomorrow.month != now.month) {
    await r.tap(find.byTooltip('Next month'), 'next month', dwellAfter: 1);
  }
  await r.tap(
    find.descendant(
      of: find.byType(DatePickerDialog),
      matching: find.text('${tomorrow.day}'),
    ),
    'tomorrow',
    dwellAfter: 1,
  );
  await r.tap(find.text('OK'), 'confirm the date', dwellAfter: 1.5);
  await r.reveal(find.text('Create pass'), 'create pass');
  await r.tap(find.text('Create pass'), 'create the pass', dwellAfter: 1);
  await r.waitFor(r.textCI('Entry code'), 'the new pass', seconds: 45);
  await r.dwell(3);
  // Sharing hands the card to the system share sheet; the button is shown,
  // not pressed.
  await r.reveal(r.textCI('Share with guest'), 'the share button');
  await r.dwell(2);
  await r.reveal(r.textCI('Cancel pass'), 'cancel pass');
  await r.tap(r.textCI('Cancel pass'), 'cancel the pass', dwellAfter: 1.5);
  await r.waitFor(find.text('Cancel this pass?'), 'the cancel dialog');
  await r.tap(
    find.descendant(
      of: find.byType(AlertDialog),
      matching: find.text('Cancel pass'),
    ),
    'confirm the cancellation',
    dwellAfter: 1,
  );
  await r.waitFor(find.text('Pass cancelled.'), 'the pass to be cancelled');
  await r.dwell(2.5);
}

Future<void> _notifications(Reviewer r) async {
  await r.tap(
    find.byIcon(Icons.notifications_outlined),
    'the bell',
    dwellAfter: 2.5,
  );
  final tiles = find.descendant(
    of: find.byType(NotificationsScreen),
    matching: find.byType(ListTile),
  );
  await r.waitFor(tiles, 'a notification');
  await r.dwell(1.5);
  await r.tap(tiles, 'the latest notification', dwellAfter: 3);
  if (!r.has(find.byType(NotificationsScreen))) {
    await r.dwell(1);
    await r.back();
    await r.dwell(1.5);
  }
}

Future<void> _marketplace(Reviewer r) async {
  await r.tap(r.navItem('Explore'), 'the Explore tab', dwellAfter: 2.5);
  await r.waitFor(find.byType(ListingCard), 'listings');
  await r.dwell(1.5);
  await r.scroll(360, dwellAfter: 1.5);
  await r.scroll(-360, dwellAfter: 1);
  if (r.has(find.byTooltip('Map view'))) {
    await r.tap(find.byTooltip('Map view'), 'the map', dwellAfter: 3.5);
    await r.tap(
      find.byTooltip('List view'),
      'back to the list',
      dwellAfter: 1.5,
    );
  }
  await r.waitFor(find.byType(BrowseScreen), 'browse');
  await r.tap(find.byType(ListingCard), 'a listing', dwellAfter: 3);
  await r.scroll(420, dwellAfter: 1.5);
  await r.scroll(-420, dwellAfter: 1);
  final heart = find.byWidgetPredicate(
    (w) =>
        w is Icon &&
        (w.icon == Icons.favorite || w.icon == Icons.favorite_border),
  );
  // On and off again: the renter's wishlist ends as it started.
  await r.tap(heart, 'the wishlist heart', dwellAfter: 2);
  await r.tap(heart, 'the wishlist heart again', dwellAfter: 2);
  await r.back();
}

Future<void> _profileArabicAndAccount(Reviewer r) async {
  Future<void> openProfile(String services, String account) async {
    await r.tap(r.navItem(services), 'the Services tab', dwellAfter: 1.5);
    await r.tap(find.text(account), 'the account page', dwellAfter: 2);
  }

  await openProfile('Services', 'Account');
  await r.reveal(find.text('عربي'), 'the language switch');
  await r.tap(find.text('عربي'), 'Arabic', dwellAfter: 2.5);
  await r.back();
  await r.tap(r.navItem('الرئيسية'), 'home in Arabic', dwellAfter: 3);
  await r.scroll(380, dwellAfter: 1.5);
  await r.scroll(-380, dwellAfter: 1);

  await openProfile('الخدمات', 'حسابي');
  await r.reveal(find.text('EN'), 'the language switch');
  await r.tap(find.text('EN'), 'English', dwellAfter: 2);
  await r.scroll(-2000, dwellAfter: 0.8);
  await r.tap(find.text('DARK'), 'dark mode', dwellAfter: 2.5);
  await r.tap(find.text('LIGHT'), 'light mode', dwellAfter: 1.5);
  // The legal pages open in the browser, so their rows are shown, not opened.
  await r.reveal(find.text('Privacy Policy'), 'privacy and terms');
  await r.dwell(2.5);
  await r.reveal(find.text('Delete account'), 'delete account');
  await r.tap(find.text('Delete account'), 'delete account', dwellAfter: 3);
  await r.waitFor(find.byType(AlertDialog), 'the delete dialog');
  // Never deleted: the dialog is read, then cancelled.
  await r.tap(
    find.descendant(
      of: find.byType(AlertDialog),
      matching: find.text('Cancel'),
    ),
    'keep the account',
    dwellAfter: 1.5,
  );
  await r.reveal(find.text('SIGN OUT'), 'sign out');
  await r.tap(find.text('SIGN OUT'), 'sign out', dwellAfter: 1.5);
  await r.tap(
    find.widgetWithText(TextButton, 'Sign out'),
    'confirm sign out',
    dwellAfter: 1,
  );
  await r.waitFor(find.text('SIGN IN'), 'the login screen');
}

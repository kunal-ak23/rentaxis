// Tutorial 27. Default role for scenes that do not name one: anonymous.
//
// The browse page redirects an unauthenticated visitor to /auth/login
// (MarketplaceContent) — it is not actually public — so every marketplace
// scene here signs in as the Tenant. Only a listing's own detail page is
// genuinely viewable anonymously (it shows a Sign In prompt instead of
// redirecting), which is out of scope for this tour.
import { tenantSlug } from '../lib/context.mjs';
import { publicRouteScene, roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('renter', `/en/marketplace/${tenantSlug}`, 'Tenant marketplace', 'Browse published inventory without exposing Company administration.', { verifyTenantContext: false }),
  roleRouteScene('renter', `/en/marketplace/${tenantSlug}`, 'Search and filters', 'Narrow listings by bedrooms, rent range, furnishing, and availability.', { verifyTenantContext: false }),
  roleRouteScene('renter', '/en/marketplace/wishlist', 'Wishlist', 'Retain saved listings privately and remove them when they are no longer relevant.', { verifyTenantContext: false }),
  publicRouteScene('/en/auth/register', 'Visitor registration', 'Create an account before saving a wishlist or sending an enquiry.'),
  publicRouteScene('/en/privacy', 'Privacy and legal information', 'Review privacy, terms, and data-deletion guidance before submitting personal information.'),
];

export default { role: 'anonymous', scenes };

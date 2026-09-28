// Tutorial 27. Default role for scenes that do not name one: anonymous.
import { tenantSlug } from '../lib/context.mjs';
import { publicRouteScene, roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('renter', `/en/marketplace/${tenantSlug}`, 'Renter marketplace', 'Browse published inventory without exposing tenant administration.', { verifyTenantContext: false }),
  roleRouteScene('renter', `/en/marketplace/${tenantSlug}`, 'Search and filters', 'Narrow listings by location, property type, rent, bedrooms, and availability.', { verifyTenantContext: false }),
  roleRouteScene('renter', '/en/marketplace/wishlist', 'Wishlist', 'Retain selected listings privately and remove them when they are no longer relevant.', { verifyTenantContext: false }),
  publicRouteScene('/en/auth/register', 'Visitor registration', 'Create an account before expressing interest or synchronising a wishlist across devices.'),
  publicRouteScene('/en/privacy', 'Privacy and legal information', 'Review privacy, terms, and data-deletion guidance before submitting personal information.'),
];

export default { role: 'anonymous', scenes };

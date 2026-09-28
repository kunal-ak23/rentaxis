// Tutorial 26. Default role for scenes that do not name one: tenantAdmin.
import { listingId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/listings', 'Listing management', 'Draft, review, publish, unpublish, and archive marketplace listings from tenant administration.'),
  routeScene(`/en/dashboard/listings/${listingId}`, 'Listing detail', 'Review media, description, rent, availability, amenities, coordinates, and SEO before publishing.'),
  routeScene('/en/dashboard/listings', 'Publication state', 'Use clear state labels to separate private drafts from public marketplace inventory.'),
];

export default { role: 'tenantAdmin', scenes };

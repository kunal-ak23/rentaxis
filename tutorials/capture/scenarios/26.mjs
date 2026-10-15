// Tutorial 26. Default role for scenes that do not name one: tenantAdmin.
import { listingId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/listings', 'Listing management', 'Draft, review, publish, unlist, and delete marketplace listings from the Listings workspace.'),
  routeScene(`/en/dashboard/listings/${listingId}`, 'Listing detail', 'Details, Media, Amenities, SEO, Pricing, and Location each hold their own part of the listing before it goes public.', async (page) => {
    await page.getByRole('tab', { name: 'Media', exact: true }).click();
  }),
  routeScene(`/en/dashboard/listings/${listingId}`, 'Enquiries', 'View Enquiries opens the interested Tenants for this listing, ready to follow up or turn into a contract.', async (page) => {
    await page.getByRole('button', { name: 'View Enquiries', exact: true }).click();
  }),
  routeScene('/en/dashboard/listings', 'Publication state', 'Publish makes a listing visible on the marketplace; Unlist hides it again without losing its enquiry history.'),
];

export default { role: 'tenantAdmin', scenes };

// Tutorial 22. Default role for scenes that do not name one: tenantAdmin.
import { ticketId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/tickets', 'Maintenance tickets', 'Prioritise work using status, category, property, unit, and requester context.'),
  routeScene(`/en/dashboard/tickets/${ticketId}`, 'Ticket collaboration', 'The Tenant and property team share replies, assignment, ETA, and status history on one record.'),
  routeScene('/en/dashboard/tickets/reports', 'Ticket reporting', 'Review workload, closure counts, response performance, and satisfaction trends.'),
];

export default { role: 'tenantAdmin', scenes };

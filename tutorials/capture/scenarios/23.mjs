// Tutorial 23. Default role for scenes that do not name one: tenantAdmin.
import { meetingId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/meetings', 'Meetings and visits', 'Schedule office visits, property viewings, and cheque-replacement appointments.'),
  routeScene(`/en/dashboard/meetings/${meetingId}`, 'Meeting detail', 'Confirm purpose, host, requester, property, unit, time slot, and current decision.'),
  routeScene('/en/dashboard/meetings', 'Calendar follow-up', 'Use status and date filters to find meetings that need approval or completion.'),
];

export default { role: 'tenantAdmin', scenes };

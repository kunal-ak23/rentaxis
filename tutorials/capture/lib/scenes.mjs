// Scene constructors. A scene is { title, body, run(page), role?, weight?, continues?, verifyTenantContext? }.
import { goto } from './page.mjs';

export function routeScene(pathname, title, body, afterNavigation, verifyTenantContext = true) {
  return {
    title,
    body,
    verifyTenantContext,
    run: async (page) => {
      await goto(page, pathname);
      if (afterNavigation) await afterNavigation(page);
    },
  };
}

/**
 * A scene that carries on in the page the previous scene left behind: no new
 * browser context, no navigation. Use it for a flow whose state lives only in
 * the page — a modal wizard, say — where starting over in a fresh context
 * would mean replaying every earlier step on camera. Consecutive scenes of the
 * same role share one context and so one video clip.
 */
export function stepScene(title, body, run, options = {}) {
  return {
    title,
    body,
    run,
    continues: true,
    role: options.role,
    weight: options.weight,
    verifyTenantContext: options.verifyTenantContext ?? true,
  };
}

export function publicRouteScene(pathname, title, body, afterNavigation) {
  return routeScene(pathname, title, body, afterNavigation, false);
}

export function roleRouteScene(role, pathname, title, body, options = {}) {
  return {
    ...routeScene(
      pathname,
      title,
      body,
      options.afterNavigation,
      options.verifyTenantContext ?? role !== 'anonymous',
    ),
    role,
    weight: options.weight,
    allowTour: options.allowTour === true,
  };
}

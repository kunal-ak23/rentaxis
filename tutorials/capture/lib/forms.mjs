// Form helpers for modal forms whose labels are plain <label> siblings of their controls.

/** The innermost element holding both the modal's heading and its form. */
export const modalForm = (page, title) => page.locator('div')
  .filter({ has: page.getByRole('heading', { name: title, exact: true }) })
  .filter({ has: page.locator('form') }).last();

const upper = (value) => `translate(normalize-space(), 'abcdefghijklmnopqrstuvwxyz', 'ABCDEFGHIJKLMNOPQRSTUVWXYZ')`
  + `="${value.toUpperCase()}"`;

/**
 * The input/select/textarea that follows a label, matched case-insensitively
 * and ignoring a trailing required-marker (" *"): labels are styled uppercase.
 */
export const fieldByLabel = (scope, label) => scope.locator(
  `xpath=.//label[${upper(label)} or ${upper(`${label} *`)}]`
  + '/following-sibling::*[1]/descendant-or-self::*[self::input or self::select or self::textarea][1]',
);

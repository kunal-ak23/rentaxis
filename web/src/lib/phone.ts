/**
 * The lenient phone rule PUT /auth/me enforces (AuthController.PHONE_PATTERN):
 * digits, spaces, "+", "-", parentheses, 7-15 digits; blank clears the phone.
 * Local UAE forms ("050 8831786", "04 123 9911") are valid. Keep the two in step.
 */
export const PHONE_PATTERN = /^\s*$|^\s*\+?[\s()-]*(?:\d[\s()-]*){7,15}$/;

export function isPlausiblePhone(value: string): boolean {
    return PHONE_PATTERN.test(value);
}

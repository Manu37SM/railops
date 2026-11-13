/** Production build configuration. Swapped for environment.development.ts in dev builds (angular.json). */
export const environment = {
  production: true,
  /** Payment gateway checkout hosts the app may redirect to. PLACEHOLDERS: set to your gateway's real hosts. */
  paymentHosts: ['checkout.razorpay.com', 'secure.payu.in'] as readonly string[],
};

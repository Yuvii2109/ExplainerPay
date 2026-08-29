/** @type {import('next').NextConfig} */
const nextConfig = {
  experimental: {
    // Do not hold a dynamic page in the client router cache. Every screen here reports live
    // state, so replaying an old payload shows a debt that has already been paid, or hides one
    // that has just been incurred.
    staleTimes: { dynamic: 0, static: 0 },
  },
};

export default nextConfig;
